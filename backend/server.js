const http = require("http");

const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || "0.0.0.0";
const OPENAI_MODEL = process.env.OPENAI_MODEL || "gpt-4.1-mini";
const OPENAI_URL = "https://api.openai.com/v1/responses";
const MAX_BODY_BYTES = Number(process.env.MAX_BODY_BYTES || 25 * 1024 * 1024);

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
  return JSON.parse(outputText);
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
      });
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
