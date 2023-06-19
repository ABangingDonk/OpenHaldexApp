package com.kong.openhaldex;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Handler;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Layout;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.github.douglasjunior.bluetoothclassiclibrary.BluetoothClassicService;
import com.github.douglasjunior.bluetoothclassiclibrary.BluetoothConfiguration;
import com.github.douglasjunior.bluetoothclassiclibrary.BluetoothService;
import com.github.douglasjunior.bluetoothclassiclibrary.BluetoothStatus;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements DeleteModeFragment.DialogListener, SetMinPedalFragment.DialogListener {

    private static final String TAG = "MainActivity";
    BluetoothConfiguration bt_config;
    BluetoothDevice bt_device = null;
    BluetoothService bt_service = null;
    private int selected_mode_button;
    private static char haldex_lock = 0;
    private static char haldex_status = 0;
    private static char target_lock = 0;
    private static char vehicle_speed = 0;
    private static boolean mode_override = false;
    private static int lockpoint_bitmask_l = 0;
    private static int lockpoint_bitmask_h = 0;
    private static int pedal_threshold = 0;
    public Mode current_mode;
    private boolean unknown_mode = true;
    private static boolean custom_ready = false;
    private static int retries = 3;
    Toast toastMessage;
    private boolean logs_shown = false;

    Handler btStatus = new Handler();
    Runnable btStatusRunnable;
    int btStatusDelay = 500;
    Handler displayUpdate = new Handler();
    Runnable displayUpdateRunnable;
    int displayUpdateDelay = 10;
    Handler modeCheck = new Handler();
    Runnable modeCheckRunnable;
    Handler lockPointCheck = new Handler();
    Runnable lockPointCheckRunnable;
    Handler sendData = new Handler();
    Runnable sendDataRunnable;

    public ArrayList<Mode> ModeList;

    private void addLog(String message)
    {
        TextView logs = findViewById(R.id.log_text);

        Log.i(TAG, message);

        logs.append(message + "\n");
        final Layout layout = logs.getLayout();
        if(layout != null){
            int scrollDelta = layout.getLineBottom(logs.getLineCount() - 1)
                    - logs.getScrollY() - logs.getHeight();
            if(scrollDelta > 0)
                logs.scrollBy(0, scrollDelta);
        }
    }

    private void makeToast(String message)
    {
        if (toastMessage != null)
        {
            toastMessage.cancel();
        }
        toastMessage = Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT);
        toastMessage.show();

        addLog(message);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.activity_main);

        bt_config = new BluetoothConfiguration();
        bt_config.context = getApplicationContext();
        bt_config.bluetoothServiceClass = BluetoothClassicService.class;
        bt_config.bufferSize = 1024;
        bt_config.characterDelimiter = 0xff;
        bt_config.deviceName = "OpenHaldex CC";
        bt_config.callListenersInMainThread = true;

        bt_config.uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"); // Required

        BluetoothService.init(bt_config);

        bt_service = BluetoothService.getDefaultInstance();

        bt_service.setOnScanCallback(new BluetoothService.OnBluetoothScanCallback() {
            @SuppressLint("MissingPermission")
            @Override
            public void onDeviceDiscovered(BluetoothDevice device, int rssi) {
                if (device != null && device.getName() != null){
                    if (device.getName().equals("OpenHaldex32") || device.getName().equals("OpenHaldexT4")){
                        addLog(String.format("startBT: device name = %s, RSSI = %d", device.getName(), rssi));
                        bt_device = device;
                        bt_service.connect(device);
                    }
                }
                else if (device.getName() == null){
                    // Do some teardown of the BT.. dunno how yet
                }
            }

            @Override
            public void onStartScan() {
                makeToast("Searching for OpenHaldex module...");
            }

            @Override
            public void onStopScan() { }
        });

        bt_service.setOnEventCallback(new BluetoothService.OnBluetoothEventCallback() {
            @Override
            public void onDataRead(byte[] buffer, int length) {
                if (bt_service.getStatus() != BluetoothStatus.NONE) {
                    receiveData(buffer, length);
                }
            }

            @Override
            public void onStatusChange(BluetoothStatus status) {
                if (status == BluetoothStatus.CONNECTED){
                    makeToast("Connected to OpenHaldex");
                } else if (status == BluetoothStatus.CONNECTING){
                    makeToast("Found OpenHaldex, connecting...");
                }
            }

            @Override
            public void onDeviceName(String deviceName) {
            }

            @Override
            public void onToast(String message) {
            }

            @Override
            public void onDataWrite(byte[] buffer) {
            }
        });

        // Try to read our private XML to get our list of modes
        if (!_getModes()){
            // Something went wrong so write the builtin XML
            _create_modesXML();
            // And try to read the XML modes again
            _getModes();
        }
    }

    public void delete_button_click(View v){
        DeleteModeFragment deleteModeFragment = new DeleteModeFragment();
        FragmentTransaction ft;

        CharSequence[] modeNames = new CharSequence[ModeList.size()];
        for (int i = 0; i < ModeList.size(); i++){
            modeNames[i] = ModeList.get(i).name;
        }
        Bundle b = new Bundle();
        b.putCharSequenceArray("modeNames", modeNames);
        b.putInt("returnID", DELETE_MODE_DIALOG);

        deleteModeFragment.setArguments(b);

        ft = getSupportFragmentManager().beginTransaction();
        Fragment prev = getSupportFragmentManager().findFragmentByTag("deleteDialog");
        if (prev != null){
            ft.remove(prev);
        }
        ft.addToBackStack(null);

        deleteModeFragment.show(ft, "deleteDialog");
    }

    public void min_ped_button_click(View v){
        SetMinPedalFragment minPedalFragment = new SetMinPedalFragment();
        FragmentTransaction ft;

        Bundle b = new Bundle();
        b.putInt("pedalThreshold", pedal_threshold);
        b.putInt("returnID", SET_MIN_PEDAL_DIALOG);

        minPedalFragment.setArguments(b);

        ft = getSupportFragmentManager().beginTransaction();
        Fragment prev = getSupportFragmentManager().findFragmentByTag("pedalDialog");
        if (prev != null){
            ft.remove(prev);
        }
        ft.addToBackStack(null);

        minPedalFragment.show(ft, "pedalDialog");
    }

    public final int DELETE_MODE_DIALOG = 0;
    public final int SET_MIN_PEDAL_DIALOG = 1;

    public void onFinishEditDialog(int source, int retval) {
        switch (source) {
            case DELETE_MODE_DIALOG:
                Mode deletedMode = ModeList.get(retval);
                if (!deletedMode.editable) {
                    makeToast(String.format("Mode '%s' cannot be deleted", deletedMode.name));
                } else {
                    _delete_mode(deletedMode, true);
                    makeToast(String.format("Mode '%s' has been deleted", deletedMode.name));
                }
                break;
            case SET_MIN_PEDAL_DIALOG:
                if (retval > 100 || retval < 0){
                    makeToast("Pick a value between 0 and 100");
                }else{
                    pedal_threshold = retval;
                    addLog(String.format("onFinishEditDialog: new pedal threshold: %d%%",retval));
                }
                break;
        }
    }

    public void add_button_click(View v){
        Intent intent = new Intent(this, ManageModes.class);
        Bundle b = new Bundle();
        b.putSerializable("modeList", ModeList);
        intent.putExtras(b);
        intent.putExtra("request_code", 0);
        startActivityForResult(intent, 0);
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data){
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode){
            case 0:
                // Handle save
                if (resultCode == RESULT_OK){
                    Mode new_mode = (Mode)data.getSerializableExtra("new_mode");
                    // Add new_mode to the private XML
                    _save_mode(new_mode);
                    assert new_mode != null;
                    makeToast(String.format("'%s' added", new_mode.name));
                }
                break;
            case 1:
                // Handle edit
                Mode old_mode = (Mode)data.getSerializableExtra("old_mode");
                if (resultCode == RESULT_OK){
                    Mode new_mode = (Mode)data.getSerializableExtra("new_mode");
                    // Delete the old mode first
                    _delete_mode(old_mode, false);
                    // Add new_mode to the private XML
                    if (current_mode != null && current_mode.name.equals(old_mode != null ? old_mode.name : null)){
                        current_mode = new_mode;
                        _save_mode(new_mode);
                        sendModeClear();
                        sendData(current_mode);
                    }
                    else{
                        _save_mode(new_mode);
                    }
                    assert new_mode != null;
                    makeToast(String.format("'%s' updated", new_mode.name));
                }
                else {
                    if (current_mode != null) {
                        assert old_mode != null;
                        if (current_mode.name.equals(old_mode.name)) {
                            ToggleButton button = findViewById(selected_mode_button);
                            button.setChecked(true);
                        }
                    }
                }
                break;
        }
    }

    private void _delete_mode(Mode mode, boolean update_list){
        try{
            InputStream inputStream = openFileInput("modes.xml");
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

            String line;
            StringBuilder updated_modes_xml = new StringBuilder();

            while ((line = bufferedReader.readLine()) != null){
                if (line.contains("\t<mode name=\"" + mode.name + "\"") &&
                    line.contains("editable=\"true\" id=\"3\">")){
                    // We've found the mode we need to delete.. so loop until we find
                    // the closing tag and then continue.
                    while(!line.equals("\t</mode>")){
                        line = bufferedReader.readLine();
                    }
                    continue;
                }
                updated_modes_xml.append(line);
                updated_modes_xml.append("\n");
            }

            FileOutputStream outputStream = openFileOutput("modes.xml", Context.MODE_PRIVATE);
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream);
            BufferedWriter bufferedWriter = new BufferedWriter(outputStreamWriter);

            bufferedWriter.append(updated_modes_xml);
            bufferedWriter.flush();

            outputStream.close();
            inputStream.close();

        }catch (IOException e){
            e.printStackTrace();
        }

        if (update_list) {
            _getModes();
        }

        if (mode.editable){
            unknown_mode = true;
        }
    }

    private final View.OnClickListener modeOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            mode_button_click(v);
        }
    };

    private final View.OnLongClickListener modeOnLongClickListener = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            mode_button_long_click(v);
            return true;
        }
    };

    private final View.OnClickListener reconnectOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            sendReconnectRequest();
        }
    };

    private final View.OnLongClickListener barOnLongClickListener = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    0
            );

            TextView logs = findViewById(R.id.log_text);
            ScrollView modes = findViewById(R.id.mode_scroller);
            // Hide logs
            if (logs_shown)
            {
                logs.setVisibility(View.GONE);
                params.weight = 0.7f;
                modes.setLayoutParams(params);
                logs_shown = false;
            }
            // Show logs
            else
            {
                logs.setVisibility(View.VISIBLE);
                params.weight = 0.4f;
                modes.setLayoutParams(params);
                logs_shown = true;
            }
            return true;
        }
    };

    public void mode_button_click(View view){
        ToggleButton previous_selection = findViewById(selected_mode_button);

        if (mode_override)
        {
            return;
        }

        if(selected_mode_button != view.getId() || current_mode == null) {
            if(previous_selection != null && previous_selection.getId() != view.getId()){
                previous_selection.setChecked(false);
            }
            selected_mode_button = view.getId();
            ToggleButton mode_button = (ToggleButton)view;
            for (Mode mode:ModeList) {
                if (mode.name == (mode_button.getText())){
                    current_mode = mode;
                }
            }
            mode_button.setChecked(true);
            if (current_mode != null && current_mode.id == 3 && bt_service.getStatus() == BluetoothStatus.CONNECTED){
                // This is a custom mode so we need to send lockpoints.. but only if BT is connected!
                sendModeClear();
                sendData(current_mode);
            }
        }
        else {
            selected_mode_button = view.getId();
            previous_selection.setChecked(true);
        }

        unknown_mode = false;
    }

    public void mode_button_long_click(View v){
        Intent intent = new Intent(this, ManageModes.class);
        Bundle b = new Bundle();
        ToggleButton mode_button = (ToggleButton)v;

        for (Mode mode:ModeList) {
            if (mode.name == (mode_button.getText())){
                if (!mode.editable){
                    makeToast(String.format("Mode '%s' cannot be edited", mode.name));
                    return;
                }
                b.putSerializable("existingMode", mode);
            }
        }
        b.putSerializable("modeList", ModeList);
        intent.putExtras(b);
        intent.putExtra("request_code", 1);
        startActivityForResult(intent, 1);
    }

    private void _createModeButtons(){
        LinearLayout mode_button_container = findViewById(R.id.mode_button_container);
        mode_button_container.removeAllViewsInLayout();
        for (Mode mode:ModeList) {
            ToggleButton button = new ToggleButton(this);
            button.setId(View.generateViewId());
            button.setTextOn(mode.name);
            button.setTextOff(mode.name);
            button.setText(mode.name);
            if (current_mode != null && current_mode.name.equals(mode.name)){
                button.setChecked(true);
                selected_mode_button = button.getId();
            }
            button.setMinHeight(250);
            button.setOnClickListener(modeOnClickListener);
            button.setOnLongClickListener(modeOnLongClickListener);
            button.setAllCaps(false);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            button.setLayoutParams(params);
            mode_button_container.addView(button);
        }
        // Select the first mode in the list
        if (current_mode == null && unknown_mode){
            selected_mode_button = mode_button_container.getChildAt(0).getId();
            ToggleButton button = findViewById(selected_mode_button);
            button.setChecked(true);
        }

        ProgressBar lock_percent_bar = findViewById(R.id.lock_percent_bar);
        lock_percent_bar.setOnLongClickListener(barOnLongClickListener);
    }

    private boolean _getModes() {
        XmlPullParserFactory pullParserFactory;

        try{
            pullParserFactory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = pullParserFactory.newPullParser();

            InputStream inputStream = openFileInput("modes.xml");
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,false);
            parser.setInput(inputStream, null);
            ModeList = parseXML(parser);
            inputStream.close();
        } catch (Exception e){
            e.printStackTrace();
            return false;
        }

        // Now create the buttons
        _createModeButtons();

        return true;
    }

    private void sendReconnectRequest(){
        try{
            bt_service.write(new byte[]{APP_MSG_CUSTOM_CTRL, DATA_CTRL_RECONNECT_BT, SERIAL_FRAME_END});
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    private void mode_overridden()
    {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        LinearLayout mode_button_container = findViewById(R.id.mode_button_container);
        mode_button_container.removeAllViewsInLayout();

        TextView message = new TextView(this);
        message.setText("Mode selection overridden by physical buttons, select stock mode before assuming control.");
        message.setGravity(Gravity.CENTER);
        message.setMinHeight(250);
        message.setLayoutParams(params);
        mode_button_container.addView(message);

        Button button = new Button(this);
        button.setId(View.generateViewId());
        button.setText("Assume control");

        button.setMinHeight(250);
        button.setOnClickListener(reconnectOnClickListener);
        button.setAllCaps(false);
        button.setLayoutParams(params);
        mode_button_container.addView(button);
    }

    private ArrayList<Mode> parseXML(XmlPullParser parser) throws XmlPullParserException, IOException {
        ArrayList<Mode> ret = null;
        int eventType = parser.getEventType();
        Mode mode = null;

        while (eventType != XmlPullParser.END_DOCUMENT){
            String element_name;
            switch (eventType){
                case XmlPullParser.START_DOCUMENT:
                    ret = new ArrayList<Mode>();
                    break;
                case XmlPullParser.START_TAG:
                    element_name = parser.getName();
                    if (element_name.equals("mode")){
                        mode = new Mode();
                        mode.name = parser.getAttributeValue(null,"name");
                        mode.editable = parser.getAttributeValue(null,"editable").equals("true");
                        mode.id = (byte)Integer.parseInt(parser.getAttributeValue(null, "id"));
                    } else if (mode != null){
                        if (element_name.equals("LockpointView")){
                            LockPoint lockPoint = new LockPoint();
                            lockPoint.speed=Integer.parseInt(parser.getAttributeValue(null,"speed"));
                            lockPoint.lock=Integer.parseInt(parser.getAttributeValue(null,"lock"));
                            lockPoint.intensity=Integer.parseInt(parser.getAttributeValue(null,"intensity"));
                            mode.lockPoints.add(lockPoint);
                        }
                    }
                    break;
                case XmlPullParser.END_TAG:
                    element_name = parser.getName();
                    if (element_name.equals("mode") && mode != null){
                        if (ret != null) {
                            ret.add(mode);
                        }
                        mode = null;
                    }
            }
            eventType = parser.next();
        }
        return ret;
    }

    private void _create_modesXML(){
        try{
            AssetManager assetManager = getAssets();
            InputStream input = assetManager.open("builtin_modes.xml");
            int size = input.available();
            byte[] buffer = new byte[size];
            input.read(buffer);
            input.close();

            FileOutputStream outputStream = openFileOutput("modes.xml", Context.MODE_PRIVATE);
            outputStream.write(buffer);
            outputStream.close();
        }catch(IOException e){
            e.printStackTrace();
        }
    }

    private void _save_mode(Mode mode){
        try{
            InputStream inputStream = openFileInput("modes.xml");
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

            String line;
            StringBuilder updated_modes_xml = new StringBuilder();

            while ((line = bufferedReader.readLine()) != null){
                if (line.equals("\t<!-- ins_mark -->")){
                    // We've found the insertion mark so add XML for the new mode here
                    updated_modes_xml.append("\t<mode name=\"");
                    updated_modes_xml.append(mode.name);
                    updated_modes_xml.append("\" ");
                    if (mode.editable){
                        updated_modes_xml.append("editable=\"true\" ");
                    }
                    else {
                        updated_modes_xml.append("editable=\"false\" ");
                    }
                    updated_modes_xml.append("id=\"3\">\n");

                    for (LockPoint lockPoint :
                            mode.lockPoints) {
                        updated_modes_xml.append("\t\t<LockpointView speed=\"");
                        updated_modes_xml.append(lockPoint.speed);
                        updated_modes_xml.append("\" lock=\"");
                        updated_modes_xml.append(lockPoint.lock);
                        updated_modes_xml.append("\" intensity=\"");
                        updated_modes_xml.append(lockPoint.intensity);
                        updated_modes_xml.append("\"/>\n");
                    }

                    updated_modes_xml.append("\t</mode>\n");
                }
                updated_modes_xml.append(line);
                updated_modes_xml.append("\n");
            }

            FileOutputStream outputStream = openFileOutput("modes.xml", Context.MODE_PRIVATE);
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream);
            BufferedWriter bufferedWriter = new BufferedWriter(outputStreamWriter);

            bufferedWriter.append(updated_modes_xml);
            bufferedWriter.flush();

            outputStream.close();
            inputStream.close();

        }catch (IOException e){
            e.printStackTrace();
        }

        _getModes();
    }

    private void bt_status_task(){
        btStatus.postDelayed(btStatusRunnable = new Runnable() {
            @Override
            public void run() {
                if (bt_service.getStatus() == BluetoothStatus.CONNECTED){
                    ToggleButton button = findViewById(R.id.connect_button);
                    button.setChecked(true);
                    disconnected = false;
                    connecting = false;
                    sendMode();
                }
                else {
                    addLog(String.format("startBT: not yet connected.. (%d)", bt_service.getStatus().ordinal()));
                }

                if(current_mode == null && unknown_mode){
                    try{
                        bt_service.write(new byte[]{APP_MSG_CUSTOM_CTRL, DATA_CTRL_CHECK_MODE, SERIAL_FRAME_END});
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                }

                btStatus.postDelayed(btStatusRunnable, btStatusDelay);
            }
        }, btStatusDelay);

        displayUpdate.postDelayed(displayUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                ProgressBar haldex_status_bar = findViewById(R.id.lock_percent_bar);
                TextView haldex_status_label = findViewById(R.id.lock_percent_label);
                TextView target_lock_label = findViewById(R.id.lock_target_label);
                TextView vehicle_speed_label = findViewById(R.id.vehicle_speed_label);

                haldex_status_bar.setProgress(haldex_lock);
                if(haldex_status != 0)
                {
                    haldex_status_label.setText(String.format("ERROR: 0x%02X", (int)haldex_status));
                    haldex_status_bar.setBackgroundColor(0xff888888);
                }
                else
                {
                    haldex_status_label.setText(String.format(Locale.ENGLISH,"Actual: %d%%", (int)haldex_lock));
                }
                if(current_mode == null || current_mode.name.equals("Stock") || mode_override){
                    target_lock_label.setText("");
                }
                else{
                    target_lock_label.setText(String.format(Locale.ENGLISH, "Target: %d%%",(int)target_lock));
                }
                vehicle_speed_label.setText(String.format(Locale.ENGLISH, "%dKPH", (int)vehicle_speed));

                displayUpdate.postDelayed(displayUpdateRunnable, displayUpdateDelay);
            }
        }, displayUpdateDelay);
    }

    static boolean connecting = false;
    static boolean disconnected = true;
    public void connect_button_click(View view){
        if (!disconnected) {
            btStatus.removeCallbacks(btStatusRunnable);
            modeCheck.removeCallbacks(modeCheckRunnable);
            lockPointCheck.removeCallbacks(lockPointCheckRunnable);

            bt_service.disconnect();
            disconnected = true;
            makeToast("Disconnected from OpenHaldex");

        }
        else {
            ToggleButton button = (ToggleButton)view;
            button.setChecked(false);
            if (!connecting)
            {
                connecting = true;

                startBT();
                bt_status_task();
            }
            else
            {
                connecting = false;

                btStatus.removeCallbacks(btStatusRunnable);
                modeCheck.removeCallbacks(modeCheckRunnable);
                lockPointCheck.removeCallbacks(lockPointCheckRunnable);
                bt_service.disconnect();
                disconnected = true;
            }
        }
    }

    private final int APP_MSG_MODE = 0;
    private final int APP_MSG_STATUS = 1;
    private final int APP_MSG_CUSTOM_DATA = 2;
    private final int APP_MSG_CUSTOM_CTRL = 3;

    private final int DATA_CTRL_CHECK_LOCKPOINTS = 0;
    private final int DATA_CTRL_CLEAR = 1;
    private final int DATA_CTRL_CHECK_MODE = 2;
    private final int DATA_CTRL_RECONNECT_BT = 3;

    private final byte SERIAL_FRAME_END = (byte)0xff;

    long last_status_time = SystemClock.elapsedRealtime();

    @SuppressLint("DefaultLocale")
    private int receiveData(byte[] data, int len){
        int message_type = -1;
        LinearLayout mode_button_container = findViewById(R.id.mode_button_container);

        if (len > 6){
            return message_type;
        }

        message_type = data[0];
        switch (message_type){
            case APP_MSG_STATUS: // 1
                haldex_status = (char)((int)data[1] & 0xff);
                haldex_lock = (char)((data[2] & 0x7f) * 100 / 72);
                target_lock = (char)data[3];
                vehicle_speed = (char)((int)data[4] & 0xff);
                boolean new_mode_override = data[5] != 0;
                if (mode_override != new_mode_override)
                {
                    mode_override = new_mode_override;
                    if (mode_override)
                    {
                        mode_overridden();
                    }
                    else
                    {
                        _getModes();
                    }
                }
                //addLog(String.format("APP_MSG_STATUS: [%x %x %x %x %x]", data[1], data[2], data[3], data[4], data[5]));
                last_status_time = SystemClock.elapsedRealtime();
                break;
            case APP_MSG_CUSTOM_CTRL: // 3
                int message_code = data[1];
                switch (message_code) {
                    case DATA_CTRL_CHECK_LOCKPOINTS:
                        int lockpoint_check_mask_l = data[2];
                        int lockpoint_check_mask_h = data[3];
                        addLog(String.format("lockpoint check: check_l 0x%x mask_l 0x%x check_h 0x%x mask_h 0x%x", lockpoint_check_mask_l, lockpoint_bitmask_l, lockpoint_check_mask_h, lockpoint_bitmask_h));
                        custom_ready = (lockpoint_check_mask_l == lockpoint_bitmask_l) && (lockpoint_check_mask_h == lockpoint_bitmask_h);
                        break;
                    case DATA_CTRL_CHECK_MODE:
                        int interceptor_mode = data[2];
                        pedal_threshold = data[3];
                        if (interceptor_mode <= 2){
                            mode_button_click(mode_button_container.getChildAt(interceptor_mode));
                        }
                        else{
                            makeToast( "Connected with custom mode active.. select stock mode");
                            mode_button_click(mode_button_container.getChildAt(0));
                        }
                        makeToast(String.format(Locale.ENGLISH, "Pedal threshold for Haldex activation set to %d%%", pedal_threshold));
                        break;
                }
            default:
                return -1;
        }

        return message_type;
    }

    private void sendMode(){
        if (bt_service.getStatus() != BluetoothStatus.CONNECTED){
            // Not connected so we're not going to be sending anything.
            return;
        }

        if (current_mode != null){
            try{
                bt_service.write(new byte[]{APP_MSG_MODE, current_mode.id, (byte)pedal_threshold, SERIAL_FRAME_END});
                addLog(String.format("Sent mode ID %d, ped_threshold %d%%", current_mode.id, (byte)pedal_threshold));
            }catch (Exception e){
                e.printStackTrace();
            }
        }
    }

    private void checkLockPoints(){
        try {
            bt_service.write(new byte[]{APP_MSG_CUSTOM_CTRL, DATA_CTRL_CHECK_LOCKPOINTS, SERIAL_FRAME_END});
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    private void sendData(final Mode mode){
        custom_ready = false;
        sendData.postDelayed(sendDataRunnable = new Runnable() {
            @Override
            public void run() {
                if(retries-- != 0 && !custom_ready){
                    try{
                        for (byte i = 0; i < mode.lockPoints.size(); i++) {
                            bt_service.write(new byte[] {
                                    APP_MSG_CUSTOM_DATA,
                                    i,
                                    (byte)mode.lockPoints.get(i).speed,
                                    (byte)mode.lockPoints.get(i).lock,
                                    (byte)mode.lockPoints.get(i).intensity,
                                    SERIAL_FRAME_END
                            });
                            if (i > 6)
                            {
                                lockpoint_bitmask_h |= (1 << (i - 7));
                            }
                            else
                            {
                                lockpoint_bitmask_l |= (1 << i);
                            }
                        }
                        checkLockPoints();
                        sendData.postDelayed(sendDataRunnable, 500);
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                }
                else if (custom_ready){
                    sendData.removeCallbacks(sendDataRunnable);
                    current_mode = mode;
                    addLog(String.format("Sent custom mode '%s' to interceptor, current_mode is %d", mode.name, current_mode.id));
                    sendMode();
                }
                else {
                    makeToast("Failed setting custom mode!!");
                }
            }
        }, 0);
    }

    private void sendModeClear(){
        try{
            bt_service.write(new byte[]{APP_MSG_CUSTOM_CTRL, DATA_CTRL_CLEAR, SERIAL_FRAME_END});
            lockpoint_bitmask_l = 0;
            lockpoint_bitmask_h = 0;
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // If request is cancelled, the result arrays are empty.
        if (grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            addLog(String.format("startBT: permission %d granted!", requestCode));
            startBT();
        } else {
            makeToast("Bluetooth permission not granted");
        }
    }

    private enum rq_codes
    {
        BLUETOOTH,
        BLUETOOTH_CONNECT,
        BLUETOOTH_SCAN,
        BLUETOOTH_ADVERTISE,
        BLUETOOTH_ADMIN,
        ACCESS_FINE_LOCATION
    }

    private boolean checkPermission(String permission, int requestCode)
    {
        if (ContextCompat.checkSelfPermission(MainActivity.this, permission) == PackageManager.PERMISSION_DENIED)
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            {
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{permission}, requestCode);
                return false;
            }

            if (requestCode == rq_codes.BLUETOOTH.ordinal())
            {
                makeToast("App does not have Bluetooth permission!");
                return false;
            }
            else if (requestCode == rq_codes.BLUETOOTH_ADMIN.ordinal())
            {
                makeToast("App does not have Bluetooth admin permission!");
                return false;
            }
            if (requestCode == rq_codes.ACCESS_FINE_LOCATION.ordinal())
            {
                makeToast("App does not have location permission!");
                return false;
            }

            return true;

        }else{
            addLog(String.format("startBT: granted permission %d", requestCode));
            return true;
        }
    }

    private boolean checkBTStatus()
    {
        BluetoothAdapter bt_adaptor = BluetoothAdapter.getDefaultAdapter();
        if (bt_adaptor == null)
        {
            makeToast("Bluetooth is not supported by this device");
        }
        else if (!bt_adaptor.isEnabled())
        {
            makeToast("Bluetooth is not enabled");
        }
        else
        {
            return true;
        }

        ToggleButton button = findViewById(R.id.connect_button);
        button.setChecked(false);
        return false;
    }

    private void startBT(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        {
            if (checkBTStatus() &&
                checkPermission(Manifest.permission.BLUETOOTH, rq_codes.BLUETOOTH.ordinal()) &&
                checkPermission(Manifest.permission.BLUETOOTH_CONNECT, rq_codes.BLUETOOTH_CONNECT.ordinal()) &&
                checkPermission(Manifest.permission.BLUETOOTH_SCAN, rq_codes.BLUETOOTH_SCAN.ordinal()) &&
                checkPermission(Manifest.permission.BLUETOOTH_ADVERTISE, rq_codes.BLUETOOTH_ADVERTISE.ordinal()))
            {
                bt_service.startScan();
            }
        }
        else
        {
            if (checkBTStatus() &&
                checkPermission(Manifest.permission.BLUETOOTH, rq_codes.BLUETOOTH.ordinal()) &&
                checkPermission(Manifest.permission.BLUETOOTH_ADMIN, rq_codes.BLUETOOTH_ADMIN.ordinal()) &&
                checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, rq_codes.ACCESS_FINE_LOCATION.ordinal()))
            {
                bt_service.startScan();
            }
        }
    }
}