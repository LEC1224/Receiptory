package se.kvittordning.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {
    private int receiptCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView counterText = findViewById(R.id.receipt_count);
        Button addButton = findViewById(R.id.add_receipt_button);

        addButton.setOnClickListener(view -> {
            receiptCount++;
            counterText.setText(getResources().getQuantityString(
                    R.plurals.receipt_count,
                    receiptCount,
                    receiptCount
            ));
        });
    }
}
