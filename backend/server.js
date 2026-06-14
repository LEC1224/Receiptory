const http = require("http");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || "0.0.0.0";
const OPENAI_MODEL = process.env.OPENAI_MODEL || "gpt-4.1-mini";
const OPENAI_URL = "https://api.openai.com/v1/responses";
const MAX_BODY_BYTES = Number(process.env.MAX_BODY_BYTES || 25 * 1024 * 1024);
const PACKAGE_NAME = process.env.GOOGLE_PLAY_PACKAGE_NAME || "se.lecani.Receiptory";
const ENTITLEMENTS_FILE = process.env.ENTITLEMENTS_FILE || path.join(__dirname, "entitlements.json");
const SCAN_PACKS = {
  ai_scans_100: { scans: 100, price: "$2" },
  ai_scans_500: { scans: 500, price: "$5" },
  ai_scans_2000: { scans: 2000, price: "$10" },
};
let googleAccessToken = null;

const REQUIRED_FIELDS = [
  "merchant",
  "date",
  "total",
  "raw_text",
  "items",
  "category_decision",
];

function sendJson(response, status, payload) {
  const body = JSON.stringify(payload);
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body),
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
  });
  response.end(body);
}

function safeUserId(userId) {
  const clean = String(userId || "").trim();
  if (!clean || clean.length > 128 || !/^[a-zA-Z0-9._:-]+$/.test(clean)) {
    const error = new Error("user_id is required.");
    error.status = 400;
    throw error;
  }
  return clean;
}

function readEntitlementDatabase() {
  try {
    const database = JSON.parse(fs.readFileSync(ENTITLEMENTS_FILE, "utf8"));
    database.users = database.users || {};
    database.purchases = database.purchases || {};
    return database;
  } catch (error) {
    return { users: {}, purchases: {} };
  }
}

function writeEntitlementDatabase(database) {
  fs.writeFileSync(ENTITLEMENTS_FILE, JSON.stringify(database, null, 2));
}

function entitlementFor(database, userId) {
  if (!database.users[userId]) {
    database.users[userId] = {
      purchased_scans: 0,
      used_scans: 0,
      updated_at: new Date().toISOString(),
    };
  }
  const user = database.users[userId];
  return {
    remaining_scans: Math.max(0, Number(user.purchased_scans || 0) - Number(user.used_scans || 0)),
    purchased_scans: Number(user.purchased_scans || 0),
    used_scans: Number(user.used_scans || 0),
  };
}

function ensureScanCredits(userId) {
  const database = readEntitlementDatabase();
  const entitlement = entitlementFor(database, userId);
  if (entitlement.remaining_scans <= 0) {
    const error = new Error("No AI scan credits remaining. Manual receipt entry is free.");
    error.status = 402;
    throw error;
  }
}

function debitScanCredit(userId) {
  const database = readEntitlementDatabase();
  entitlementFor(database, userId);
  database.users[userId].used_scans = Number(database.users[userId].used_scans || 0) + 1;
  database.users[userId].updated_at = new Date().toISOString();
  writeEntitlementDatabase(database);
  return entitlementFor(database, userId);
}

function grantScanPack(userId, productId, purchaseToken, googlePurchase) {
  const pack = SCAN_PACKS[productId];
  if (!pack) {
    const error = new Error("Unknown scan pack product_id.");
    error.status = 400;
    throw error;
  }

  const database = readEntitlementDatabase();
  entitlementFor(database, userId);
  if (database.purchases[purchaseToken]) {
    return {
      ok: true,
      already_granted: true,
      granted_scans: 0,
      entitlement: entitlementFor(database, userId),
    };
  }

  database.users[userId].purchased_scans = Number(database.users[userId].purchased_scans || 0) + pack.scans;
  database.users[userId].updated_at = new Date().toISOString();
  database.purchases[purchaseToken] = {
    user_id: userId,
    product_id: productId,
    scans: pack.scans,
    order_id: googlePurchase.orderId || "",
    purchase_time_millis: googlePurchase.purchaseTimeMillis || "",
    granted_at: new Date().toISOString(),
  };
  writeEntitlementDatabase(database);

  return {
    ok: true,
    already_granted: false,
    granted_scans: pack.scans,
    entitlement: entitlementFor(database, userId),
  };
}

function base64Url(value) {
  return Buffer.from(value)
    .toString("base64")
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");
}

function readServiceAccount() {
  const raw = process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON;
  if (raw) {
    return JSON.parse(raw.trim().startsWith("{") ? raw : fs.readFileSync(raw, "utf8"));
  }
  if (process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_FILE) {
    return JSON.parse(fs.readFileSync(process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_FILE, "utf8"));
  }
  const error = new Error("Google Play service account JSON is not configured.");
  error.status = 500;
  throw error;
}

async function getGoogleAccessToken() {
  if (googleAccessToken && googleAccessToken.expiresAt > Date.now() + 60000) {
    return googleAccessToken.token;
  }

  const serviceAccount = readServiceAccount();
  const now = Math.floor(Date.now() / 1000);
  const header = { alg: "RS256", typ: "JWT" };
  const claim = {
    iss: serviceAccount.client_email,
    scope: "https://www.googleapis.com/auth/androidpublisher",
    aud: "https://oauth2.googleapis.com/token",
    exp: now + 3600,
    iat: now,
  };
  const unsigned = `${base64Url(JSON.stringify(header))}.${base64Url(JSON.stringify(claim))}`;
  const signature = crypto
    .createSign("RSA-SHA256")
    .update(unsigned)
    .sign(serviceAccount.private_key, "base64")
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");

  const tokenResponse = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${unsigned}.${signature}`,
    }),
  });
  const tokenJson = await tokenResponse.json();
  if (!tokenResponse.ok) {
    throw new Error(`Google OAuth failed: ${JSON.stringify(tokenJson)}`);
  }
  googleAccessToken = {
    token: tokenJson.access_token,
    expiresAt: Date.now() + Number(tokenJson.expires_in || 3600) * 1000,
  };
  return googleAccessToken.token;
}

async function verifyGooglePlayPurchase(productId, purchaseToken) {
  if (!SCAN_PACKS[productId]) {
    const error = new Error("Unknown scan pack product_id.");
    error.status = 400;
    throw error;
  }
  if (!purchaseToken || typeof purchaseToken !== "string") {
    const error = new Error("purchase_token is required.");
    error.status = 400;
    throw error;
  }

  const accessToken = await getGoogleAccessToken();
  const baseUrl = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(PACKAGE_NAME)}/purchases/products/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}`;
  const purchaseResponse = await fetch(baseUrl, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  const purchase = await purchaseResponse.json();
  if (!purchaseResponse.ok) {
    const error = new Error(`Google Play purchase validation failed: ${JSON.stringify(purchase)}`);
    error.status = 502;
    throw error;
  }
  if (purchase.purchaseState !== 0) {
    const error = new Error("Google Play purchase is not completed.");
    error.status = 400;
    throw error;
  }

  return purchase;
}

async function consumeGooglePlayPurchase(productId, purchaseToken) {
  const accessToken = await getGoogleAccessToken();
  const consumeUrl = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(PACKAGE_NAME)}/purchases/products/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}:consume`;
  const consumeResponse = await fetch(consumeUrl, {
    method: "POST",
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!consumeResponse.ok) {
    const text = await consumeResponse.text();
    throw new Error(`Google Play consume failed: ${text}`);
  }
}

function readBody(request) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    request.on("data", (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(new Error("Request body is too large."));
        request.destroy();
        return;
      }
      chunks.push(chunk);
    });
    request.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    request.on("error", reject);
  });
}

function buildInstructions(categories, allowNewCategories) {
  const categoryLines = categories
    .map((category) => `- id: ${category.id}, name: ${category.name}`)
    .join("\n");
  const categoryMode = allowNewCategories
    ? [
        "Choose the best existing category if one fits.",
        "If none fit, set category_decision.action to create_new, leave existing_category_id empty, and provide a concise reusable category name in new_category_name.",
        "If using an existing category, set action to use_existing, provide its id, and leave new_category_name empty.",
      ].join(" ")
    : [
        "You MUST place this receipt into one of the existing categories provided below.",
        "Do not create or propose a new category under any circumstances.",
        "Always set category_decision.action to use_existing, set existing_category_id to exactly one supplied category id, and set new_category_name to an empty string.",
      ].join(" ");

  return [
    "You extract purchase data from receipt photos for an expense manager.",
    "Return the receipt merchant, ISO date, item rows, total, and raw transcribed text.",
    categoryMode,
    "Existing categories:",
    categoryLines,
  ].join("\n");
}

function buildTextFormat() {
  return {
    format: {
      type: "json_schema",
      name: "receipt_extraction",
      strict: true,
      schema: {
        type: "object",
        additionalProperties: false,
        required: REQUIRED_FIELDS,
        properties: {
          merchant: { type: "string" },
          date: {
            type: "string",
            description: "Receipt date in YYYY-MM-DD format. If unknown, use today's best estimate from the receipt context.",
          },
          total: { type: "number" },
          raw_text: {
            type: "string",
            description: "Readable text transcribed from the receipt.",
          },
          items: {
            type: "array",
            items: {
              type: "object",
              additionalProperties: false,
              required: ["name", "cost"],
              properties: {
                name: { type: "string" },
                cost: { type: "number" },
              },
            },
          },
          category_decision: {
            type: "object",
            additionalProperties: false,
            required: ["action", "existing_category_id", "new_category_name"],
            properties: {
              action: {
                type: "string",
                enum: ["use_existing", "create_new"],
              },
              existing_category_id: {
                type: "string",
                description: "When action is use_existing, this must be one of the supplied category ids. Otherwise empty string.",
              },
              new_category_name: {
                type: "string",
                description: "When action is create_new, this is the proposed category name. Otherwise empty string.",
              },
            },
          },
        },
      },
    },
  };
}

function buildOpenAiRequest(payload) {
  const categories = Array.isArray(payload.categories) ? payload.categories : [];
  return {
    model: OPENAI_MODEL,
    instructions: buildInstructions(categories, payload.allow_new_categories !== false),
    input: [
      {
        role: "user",
        content: [
          {
            type: "input_text",
            text: "Extract this receipt, categorize it, and return only data matching the schema.",
          },
          {
            type: "input_image",
            image_url: `data:image/jpeg;base64,${payload.image_base64}`,
          },
        ],
      },
    ],
    text: buildTextFormat(),
  };
}

function extractOutputText(openAiResponse) {
  for (const output of openAiResponse.output || []) {
    if (output.type !== "message") {
      continue;
    }
    for (const content of output.content || []) {
      if (content.type === "output_text") {
        return content.text || "{}";
      }
    }
  }
  return "{}";
}

async function extractReceipt(payload) {
  if (!process.env.OPENAI_API_KEY) {
    throw new Error("OPENAI_API_KEY is not set on the backend PC.");
  }
  if (!payload || typeof payload.image_base64 !== "string" || payload.image_base64.length === 0) {
    const error = new Error("image_base64 is required.");
    error.status = 400;
    throw error;
  }
  const userId = safeUserId(payload.user_id);
  ensureScanCredits(userId);

  const openAiResponse = await fetch(OPENAI_URL, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${process.env.OPENAI_API_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(buildOpenAiRequest(payload)),
  });
  const responseText = await openAiResponse.text();
  if (!openAiResponse.ok) {
    const error = new Error(`OpenAI request failed: ${responseText}`);
    error.status = 502;
    throw error;
  }

  const outputText = extractOutputText(JSON.parse(responseText));
  const extraction = JSON.parse(outputText);
  debitScanCredit(userId);
  return extraction;
}

const server = http.createServer(async (request, response) => {
  try {
    if (request.method === "OPTIONS") {
      sendJson(response, 204, {});
      return;
    }
    if (request.method === "GET" && request.url === "/health") {
      sendJson(response, 200, {
        ok: true,
        model: OPENAI_MODEL,
        hasOpenAiKey: Boolean(process.env.OPENAI_API_KEY),
        hasGooglePlayCredentials: Boolean(
          process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON || process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_FILE
        ),
      });
      return;
    }
    if (request.method === "POST" && request.url === "/entitlements") {
      const body = await readBody(request);
      const payload = JSON.parse(body || "{}");
      const database = readEntitlementDatabase();
      sendJson(response, 200, entitlementFor(database, safeUserId(payload.user_id)));
      return;
    }
    if (request.method === "POST" && request.url === "/purchases/verify") {
      const body = await readBody(request);
      const payload = JSON.parse(body || "{}");
      const userId = safeUserId(payload.user_id);
      const productId = String(payload.product_id || "").trim();
      const purchaseToken = String(payload.purchase_token || "").trim();
      const googlePurchase = await verifyGooglePlayPurchase(productId, purchaseToken);
      const result = grantScanPack(userId, productId, purchaseToken, googlePurchase);
      if (!result.already_granted) {
        try {
          await consumeGooglePlayPurchase(productId, purchaseToken);
        } catch (consumeError) {
          console.warn(consumeError.message || consumeError);
        }
      }
      sendJson(response, 200, result);
      return;
    }
    if (request.method === "POST" && request.url === "/extract") {
      const body = await readBody(request);
      const payload = JSON.parse(body || "{}");
      const extraction = await extractReceipt(payload);
      sendJson(response, 200, extraction);
      return;
    }
    sendJson(response, 404, { error: "Not found." });
  } catch (error) {
    sendJson(response, error.status || 500, { error: error.message || "Backend error." });
  }
});

server.listen(PORT, HOST, () => {
  console.log(`Receiptory backend listening on http://${HOST}:${PORT}`);
});
