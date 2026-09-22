package com.wilsons.manualzreport;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final int REQUEST_BLUETOOTH_CONNECT = 1001;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket printerSocket;
    private OutputStream printerOutput;
    private boolean connectingPrinter = false;
    private boolean pendingPrint = false;
    private boolean pendingReprint = false;

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.UK);
    private SharedPreferences prefs;

    private EditText shopName, posName, operator, shiftNumber, opened, closed;
    private EditText startingCash, cashPayments, cashRefunds, paidIn, paidOut, actualCash;
    private EditText grossSales, refunds, discounts, cardPayments;
    private TextView calculations;
    private TextView printerStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("manual_z_report", MODE_PRIVATE);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        buildUi();

        if (bluetoothAdapter == null) {
            updatePrinterStatus("Bluetooth printer: Bluetooth not available");
        } else if (!hasBluetoothPermission()) {
            requestBluetoothPermission();
        } else {
            showSavedPrinterStatus();
        }
    }

    @Override
    protected void onDestroy() {
        closePrinterConnection();
        super.onDestroy();
    }

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH_CONNECT);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_CONNECT) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showSavedPrinterStatus();
            } else {
                updatePrinterStatus("Bluetooth printer: permission required");
                Toast.makeText(this, "Please allow Bluetooth permission so the app can use your paired printer.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showSavedPrinterStatus() {
        String name = prefs.getString("printerName", "");
        String address = prefs.getString("printerAddress", "");
        if (address.isEmpty()) {
            updatePrinterStatus("Bluetooth printer: not selected");
        } else {
            updatePrinterStatus("Bluetooth printer: " + (name.isEmpty() ? address : name) + " (not connected)");
        }
    }

    private void chooseBluetoothPrinter() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available on this till.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasBluetoothPermission()) {
            requestBluetoothPermission();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Turn Bluetooth on first, then try again.", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
            if (paired == null || paired.isEmpty()) {
                Toast.makeText(this, "No paired Bluetooth devices found. Pair the printer in Android Bluetooth settings first.", Toast.LENGTH_LONG).show();
                return;
            }

            ArrayList<BluetoothDevice> devices = new ArrayList<>(paired);
            String[] labels = new String[devices.size()];
            for (int i = 0; i < devices.size(); i++) {
                BluetoothDevice d = devices.get(i);
                String n = d.getName();
                labels[i] = (n == null || n.trim().isEmpty() ? "Bluetooth device" : n) + "\n" + d.getAddress();
            }

            new AlertDialog.Builder(this)
                    .setTitle("Select your receipt printer")
                    .setItems(labels, (dialog, which) -> {
                        BluetoothDevice d = devices.get(which);
                        String n = d.getName();
                        if (n == null || n.trim().isEmpty()) n = "Bluetooth printer";
                        prefs.edit()
                                .putString("printerName", n)
                                .putString("printerAddress", d.getAddress())
                                .apply();
                        connectToPrinter(d.getAddress());
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (SecurityException e) {
            requestBluetoothPermission();
        }
    }

    private void connectSavedPrinter() {
        String address = prefs.getString("printerAddress", "");
        if (address.isEmpty()) {
            chooseBluetoothPrinter();
            return;
        }
        connectToPrinter(address);
    }

    private void connectToPrinter(String address) {
        if (!hasBluetoothPermission()) {
            requestBluetoothPermission();
            return;
        }
        if (connectingPrinter) return;

        closePrinterConnection();
        connectingPrinter = true;
        String name = prefs.getString("printerName", "Bluetooth printer");
        updatePrinterStatus("Bluetooth printer: connecting to " + name + "...");

        new Thread(() -> {
            try {
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                OutputStream out = socket.getOutputStream();
                printerSocket = socket;
                printerOutput = out;
                connectingPrinter = false;
                runOnUiThread(() -> {
                    updatePrinterStatus("Bluetooth printer: " + name + " connected");
                    Toast.makeText(MainActivity.this, "Bluetooth printer connected", Toast.LENGTH_SHORT).show();
                    if (pendingPrint) {
                        boolean reprint = pendingReprint;
                        pendingPrint = false;
                        pendingReprint = false;
                        printReport(reprint);
                    }
                });
            } catch (Exception e) {
                connectingPrinter = false;
                closePrinterConnection();
                runOnUiThread(() -> {
                    updatePrinterStatus("Bluetooth printer: connection failed");
                    Toast.makeText(MainActivity.this, "Could not connect to printer: " + safeMessage(e), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private boolean printerConnected() {
        return printerSocket != null && printerSocket.isConnected() && printerOutput != null;
    }

    private void closePrinterConnection() {
        try {
            if (printerOutput != null) printerOutput.close();
        } catch (Exception ignored) { }
        try {
            if (printerSocket != null) printerSocket.close();
        } catch (Exception ignored) { }
        printerOutput = null;
        printerSocket = null;
    }

    private void updatePrinterStatus(String value) {
        if (printerStatus != null) printerStatus.setText(value);
    }

    private String safeMessage(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.trim().isEmpty() ? e.getClass().getSimpleName() : msg;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(30));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Manual Z Report v1.2");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(6));
        root.addView(title);

        printerStatus = new TextView(this);
        printerStatus.setText("Bluetooth printer: checking...");
        printerStatus.setTextSize(17);
        printerStatus.setGravity(Gravity.CENTER);
        printerStatus.setPadding(0, 0, 0, dp(6));
        root.addView(printerStatus);

        LinearLayout printerButtons = new LinearLayout(this);
        printerButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button selectPrinter = button("SELECT BLUETOOTH PRINTER");
        selectPrinter.setOnClickListener(v -> chooseBluetoothPrinter());
        printerButtons.addView(selectPrinter, weightedButtonParams());
        Button connectPrinter = button("CONNECT PRINTER");
        connectPrinter.setOnClickListener(v -> connectSavedPrinter());
        printerButtons.addView(connectPrinter, weightedButtonParams());
        root.addView(printerButtons);

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
        calculations.setPadding(0, dp(10), 0, dp(12));
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

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        return lp;
    }

    private void addSection(LinearLayout root, String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(21);
        t.setPadding(0, dp(14), 0, dp(5));
        root.addView(t);
    }

    private EditText addTextField(LinearLayout root, String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(18);
        e.setSingleLine(true);
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private EditText addMoneyField(LinearLayout root, String hint, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView pound = new TextView(this);
        pound.setText("£");
        pound.setTextSize(22);
        pound.setGravity(Gravity.CENTER);
        pound.setPadding(dp(6), 0, dp(6), 0);
        row.addView(pound, new LinearLayout.LayoutParams(dp(42), LinearLayout.LayoutParams.MATCH_PARENT));

        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(18);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        e.setPadding(dp(8), dp(8), dp(10), dp(8));
        row.addView(e, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(17);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        lp.setMargins(0, dp(6), 0, 0);
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
        if (!printerConnected()) {
            pendingPrint = true;
            pendingReprint = reprintLast;
            connectSavedPrinter();
            if (!prefs.getString("printerAddress", "").isEmpty()) {
                Toast.makeText(this, "Connecting to Bluetooth printer...", Toast.LENGTH_SHORT).show();
            }
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

        final String finalReport = report;
        new Thread(() -> {
            try {
                byte[] init = new byte[]{0x1B, 0x40};
                byte[] alignLeft = new byte[]{0x1B, 0x61, 0x00};
                byte[] feed = new byte[]{0x0A, 0x0A, 0x0A, 0x0A};
                byte[] cut = new byte[]{0x1D, 0x56, 0x00};

                printerOutput.write(init);
                printerOutput.write(alignLeft);
                printerOutput.write(finalReport.getBytes(Charset.forName("CP437")));
                printerOutput.write(feed);
                printerOutput.write(cut);
                printerOutput.flush();

                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        reprintLast ? "Reprinting last report" : "Z report sent to Bluetooth printer",
                        Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                closePrinterConnection();
                runOnUiThread(() -> {
                    showSavedPrinterStatus();
                    Toast.makeText(MainActivity.this, "Printer error: " + safeMessage(e), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
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
