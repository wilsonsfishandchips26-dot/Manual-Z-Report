package com.wilsons.manualzreport;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.sunmi.peripheral.printer.InnerPrinterCallback;
import com.sunmi.peripheral.printer.InnerPrinterManager;
import com.sunmi.peripheral.printer.SunmiPrinterService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private SunmiPrinterService printer;
    private boolean printerBinding = false;
    private boolean pendingPrint = false;
    private boolean pendingReprint = false;

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.UK);
    private SharedPreferences prefs;

    private EditText shopName, posName, operator, shiftNumber, opened, closed;
    private EditText startingCash, cashPayments, cashRefunds, paidIn, paidOut, actualCash;
    private EditText grossSales, refunds, discounts, cardPayments;
    private TextView calculations;
    private TextView printerStatus;

    private final InnerPrinterCallback printerCallback = new InnerPrinterCallback() {
        @Override
        protected void onConnected(SunmiPrinterService service) {
            printer = service;
            printerBinding = false;
            runOnUiThread(() -> {
                updatePrinterStatus("Printer: connected");
                Toast.makeText(MainActivity.this, "SUNMI printer connected", Toast.LENGTH_SHORT).show();
                if (pendingPrint) {
                    boolean reprint = pendingReprint;
                    pendingPrint = false;
                    pendingReprint = false;
                    printReport(reprint);
                }
            });
        }

        @Override
        protected void onDisconnected() {
            printer = null;
            printerBinding = false;
            runOnUiThread(() -> updatePrinterStatus("Printer: disconnected"));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("manual_z_report", MODE_PRIVATE);
        buildUi();
        connectPrinter();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (printer == null) {
            connectPrinter();
        }
    }

    @Override
    protected void onDestroy() {
        try {
            InnerPrinterManager.getInstance().unBindService(this, printerCallback);
        } catch (Exception ignored) { }
        super.onDestroy();
    }

    private void connectPrinter() {
        if (printer != null) {
            updatePrinterStatus("Printer: connected");
            return;
        }
        if (printerBinding) {
            updatePrinterStatus("Printer: connecting...");
            return;
        }

        printerBinding = true;
        updatePrinterStatus("Printer: connecting...");
        try {
            boolean started = InnerPrinterManager.getInstance().bindService(this, printerCallback);
            if (!started) {
                printerBinding = false;
                updatePrinterStatus("Printer: SUNMI service not found");
            }
        } catch (Exception e) {
            printerBinding = false;
            updatePrinterStatus("Printer: connection failed");
            Toast.makeText(this, "SUNMI printer connection error: " + safeMessage(e), Toast.LENGTH_LONG).show();
        }
    }

    private void updatePrinterStatus(String value) {
        if (printerStatus != null) {
            printerStatus.setText(value);
        }
    }

    private String safeMessage(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.trim().isEmpty() ? e.getClass().getSimpleName() : msg;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(36));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Manual Z Report");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        printerStatus = new TextView(this);
        printerStatus.setText("Printer: connecting...");
        printerStatus.setTextSize(18);
        printerStatus.setGravity(Gravity.CENTER);
        printerStatus.setPadding(0, 0, 0, dp(8));
        root.addView(printerStatus);

        Button reconnectPrinter = button("RECONNECT PRINTER");
        reconnectPrinter.setOnClickListener(v -> {
            try {
                if (printer != null || printerBinding) {
                    InnerPrinterManager.getInstance().unBindService(this, printerCallback);
                }
            } catch (Exception ignored) { }
            printer = null;
            printerBinding = false;
            connectPrinter();
        });
        root.addView(reconnectPrinter);

        addSection(root, "Shop settings");
        shopName = addTextField(root, "Shop name", prefs.getString("shopName", "Wilsons Fish N Chips"));
        posName = addTextField(root, "POS name", prefs.getString("posName", "WILSONS"));
        operator = addTextField(root, "Operator", prefs.getString("operator", "OWNER"));

        addSection(root, "Shift report");
        shiftNumber = addTextField(root, "Shift number", prefs.getString("shiftNumber", "1"));
        String now = dateFormat.format(new Date());
        opened = addTextField(root, "Shift opened", now);
        closed = addTextField(root, "Shift closed", now);

        addSection(root, "Cash drawer");
        startingCash = addMoneyField(root, "Starting cash", prefs.getString("startingCash", "150.00"));
        cashPayments = addMoneyField(root, "Cash payments", "0.00");
        cashRefunds = addMoneyField(root, "Cash refunds", "0.00");
        paidIn = addMoneyField(root, "Paid in", "0.00");
        paidOut = addMoneyField(root, "Paid out", "0.00");
        actualCash = addMoneyField(root, "Actual cash amount", "0.00");

        addSection(root, "Sales summary");
        grossSales = addMoneyField(root, "Gross sales", "0.00");
        refunds = addMoneyField(root, "Refunds", "0.00");
        discounts = addMoneyField(root, "Discounts", "0.00");
        cardPayments = addMoneyField(root, "Card", "0.00");

        calculations = new TextView(this);
        calculations.setTextSize(18);
        calculations.setPadding(0, dp(12), 0, dp(16));
        root.addView(calculations);

        Button calculate = button("CALCULATE / PREVIEW");
        calculate.setOnClickListener(v -> updateCalculations());
        root.addView(calculate);

        Button print = button("PRINT Z REPORT");
        print.setOnClickListener(v -> printReport(false));
        root.addView(print);

        Button reprint = button("REPRINT LAST REPORT");
        reprint.setOnClickListener(v -> printReport(true));
        root.addView(reprint);

        Button saveSettings = button("SAVE SHOP SETTINGS");
        saveSettings.setOnClickListener(v -> {
            prefs.edit()
                    .putString("shopName", text(shopName))
                    .putString("posName", text(posName))
                    .putString("operator", text(operator))
                    .putString("shiftNumber", text(shiftNumber))
                    .putString("startingCash", text(startingCash))
                    .apply();
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
        });
        root.addView(saveSettings);

        updateCalculations();
        setContentView(scroll);
    }

    private void addSection(LinearLayout root, String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(21);
        t.setPadding(0, dp(18), 0, dp(6));
        root.addView(t);
    }

    private EditText addTextField(LinearLayout root, String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(18);
        e.setSingleLine(true);
        e.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private EditText addMoneyField(LinearLayout root, String hint, String value) {
        EditText e = addTextField(root, hint, value);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return e;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        lp.setMargins(0, dp(8), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void updateCalculations() {
        double expected = money(startingCash) + money(cashPayments) - money(cashRefunds) + money(paidIn) - money(paidOut);
        double difference = money(actualCash) - expected;
        double net = money(grossSales) - money(refunds) - money(discounts);
        double paymentsTotal = money(cashPayments) + money(cardPayments);
        calculations.setText(
                "Expected cash: " + pounds(expected) +
                "\nDifference: " + pounds(difference) +
                "\nNet sales: " + pounds(net) +
                "\nCash + Card: " + pounds(paymentsTotal));
    }

    private void printReport(boolean reprintLast) {
        if (printer == null) {
            pendingPrint = true;
            pendingReprint = reprintLast;
            connectPrinter();
            Toast.makeText(this, "Connecting to SUNMI printer...", Toast.LENGTH_SHORT).show();
            return;
        }

        String report;
        if (reprintLast) {
            report = prefs.getString("lastReport", "");
            if (report.isEmpty()) {
                Toast.makeText(this, "No saved report to reprint", Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            updateCalculations();
            report = buildReport();
            prefs.edit().putString("lastReport", report).apply();
        }

        try {
            printer.printerInit(null);
            printer.setAlignment(0, null);
            printer.setFontSize(24f, null);
            printer.printText(report, null);
            printer.lineWrap(4, null);
            try { printer.cutPaper(null); } catch (Exception ignored) { }
            Toast.makeText(this, reprintLast ? "Reprinting last report" : "Z report sent to printer", Toast.LENGTH_SHORT).show();
        } catch (RemoteException e) {
            printer = null;
            updatePrinterStatus("Printer: disconnected");
            Toast.makeText(this, "Printer error: " + safeMessage(e), Toast.LENGTH_LONG).show();
        }
    }

    private String buildReport() {
        double expected = money(startingCash) + money(cashPayments) - money(cashRefunds) + money(paidIn) - money(paidOut);
        double difference = money(actualCash) - expected;
        double net = money(grossSales) - money(refunds) - money(discounts);
        String line = "--------------------------------\n";

        StringBuilder s = new StringBuilder();
        s.append(center(text(shopName), 32)).append("\n\n");
        s.append(center("Shift report", 32)).append("\n\n");
        s.append(row("Shift number: " + text(shiftNumber), "", 32));
        s.append(row("POS: " + text(posName), "", 32));
        s.append(line);
        s.append("Shift opened\n");
        s.append(row(text(operator), text(opened), 32));
        s.append("\nShift closed\n");
        s.append(row(text(operator), text(closed), 32));
        s.append(line);
        s.append(center("Cash drawer", 32)).append("\n");
        s.append(line);
        s.append(row("Starting cash", pounds(money(startingCash)), 32));
        s.append(row("Cash payments", pounds(money(cashPayments)), 32));
        s.append(row("Cash refunds", pounds(money(cashRefunds)), 32));
        s.append(row("Paid in", pounds(money(paidIn)), 32));
        s.append(row("Paid out", pounds(money(paidOut)), 32));
        s.append(row("Expected cash amount", pounds(expected), 32));
        s.append(row("Actual cash amount", pounds(money(actualCash)), 32));
        s.append(row("Difference", pounds(difference), 32));
        s.append(line);
        s.append(center("Sales summary", 32)).append("\n");
        s.append(line);
        s.append(row("Gross sales", pounds(money(grossSales)), 32));
        s.append(row("Refunds", pounds(money(refunds)), 32));
        s.append(row("Discounts", pounds(money(discounts)), 32));
        s.append(row("Net sales", pounds(net), 32));
        s.append(row("Cash", pounds(money(cashPayments)), 32));
        s.append(row("Card", pounds(money(cardPayments)), 32));
        s.append(line);
        s.append(center(dateFormat.format(new Date()), 32)).append("\n");
        return s.toString();
    }

    private String row(String left, String right, int width) {
        if (right == null || right.isEmpty()) return left + "\n";
        int spaces = Math.max(1, width - left.length() - right.length());
        return left + repeat(' ', spaces) + right + "\n";
    }

    private String center(String value, int width) {
        if (value.length() >= width) return value;
        int left = (width - value.length()) / 2;
        return repeat(' ', left) + value;
    }

    private String repeat(char c, int count) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < count; i++) b.append(c);
        return b.toString();
    }

    private String text(EditText e) {
        return e.getText().toString().trim();
    }

    private double money(EditText e) {
        try {
            String v = text(e).replace("£", "").replace(",", "");
            return v.isEmpty() ? 0.0 : Double.parseDouble(v);
        } catch (Exception ex) {
            return 0.0;
        }
    }

    private String pounds(double value) {
        return String.format(Locale.UK, "%s£%,.2f", value < 0 ? "-" : "", Math.abs(value));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
