package de.fh_wedel.phone_tracker;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;


public class ListWLAN extends Activity {
    private static final String TAG = "ListWLANActivity";

    public static final String STATE_KEY_CONFIG = "config";

    private ListWLANConfig config;

    WifiManager wifi;
    TextView textView;
    EditText editTextComment;
    EditText editTextServerUrl;
    CheckBox checkBoxAutosend;
    CheckBox checkBoxShowBSSIDs;

    ScanResult[] bufferedResults;

    ServiceConnection serviceConnection;
    GatherBSSID serviceBinder = null;

    /* Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                GatherBSSID.LocalBinder localBinder = (GatherBSSID.LocalBinder) service;
                serviceBinder = localBinder.getService();
                serviceBinder.setConfig(config);
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                serviceBinder = null;
            }
        };


        bindService(new Intent(ListWLAN.this, GatherBSSID.class), serviceConnection, 0);

        // Possibly read configuration from previous state
        if (savedInstanceState != null) {
            config = savedInstanceState.getParcelable(STATE_KEY_CONFIG);
        }

        // If there was no previous state: Use default configuration
        if (config == null) {
            config = new ListWLANConfig();
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onNewResults((ScanResult[]) intent.getExtras().getParcelableArray(GatherBSSID.KEY_BROADCAST_SCANS));
                onSendFinished(intent.getExtras().getString(GatherBSSID.KEY_BROADCAST_SENT, null));
            }
        }, new IntentFilter(GatherBSSID.BROADCAST_ACTION));


        setContentView(R.layout.activity_list_wlan);

        textView = (TextView) findViewById(R.id.textViewLog);
        editTextComment = (EditText) findViewById(R.id.textComment);

        // Wire up the UI for the server URL
        editTextServerUrl = (EditText) findViewById(R.id.textServer);
        editTextServerUrl.setText(config.getServerUrl());
        editTextServerUrl.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                config.setServerUrl(charSequence.toString());
            }

            @Override
            public void afterTextChanged(Editable editable) {

            }
        });


        checkBoxAutosend = (CheckBox) findViewById(R.id.checkBoxAutosend);
        checkBoxAutosend.setChecked(config.getAutoSend());
        checkBoxAutosend.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                config.setAutoSend(isChecked);
            }
        });
        checkBoxShowBSSIDs = (CheckBox) findViewById(R.id.checkBoxShowBSSIDs);
        checkBoxShowBSSIDs.setChecked(config.getShowBSSIDs());
        checkBoxShowBSSIDs.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                config.setShowBSSIDs(isChecked);
            }
        });
        
        final Button buttonClear = (Button) findViewById(R.id.buttonClear);
        buttonClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ListWLAN.this.onClear();
            }
        });

        // Wire up the start scanning button to
        Button buttonStart = (Button) findViewById(R.id.buttonStart);
        buttonStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (checkWifi()) {
                    Intent intent = new Intent(ListWLAN.this, GatherBSSID.class);
                    intent.putExtra(STATE_KEY_CONFIG, config);

                    startService(intent);
                }
            }
        });
        Button buttonStop = (Button) findViewById(R.id.buttonStop);
        buttonStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(ListWLAN.this, GatherBSSID.class);
                stopService(intent);
            }
        });
        Button buttonSend = (Button) findViewById(R.id.buttonSend);
        buttonSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (serviceBinder != null) {
                    serviceBinder.sendLatestResults();
                }
            }
        });

        wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);




        // TODO: register for network change. disable on wrong ssid or no connection
    }

    /**
     * Called to retrieve per-instance state from an activity before being killed so that the state
     * can be restored in onCreate(Bundle) or onRestoreInstanceState(Bundle) (the Bundle populated
     * by this method will be passed to both).
     *
     * @param outState The bundle to persist to
     */
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(STATE_KEY_CONFIG, config);
    }

    private boolean checkWifi() {
        if (wifi.isWifiEnabled() == false) {
            textView.append("wifi is disabled\n");
            return false;
        } else {
            //textView.append("wifi is enabled\n");
            WifiInfo info = wifi.getConnectionInfo();
            String ssid = info.getSSID();
            if (ssid == null) {
                textView.append("unable to read SSID\n");
                return false;
            } else {
                ssid = ssid.trim();
                textView.append("Connected SSID is " + ssid + "\n");
                ssid = ssid.replace("\"", "");
                if (config.isInterestingSSID(ssid)) {
                    //textView.append("SSID okay\n");
                    return true;
                } else {
                    textView.append("SSID is not interesting\n");
                    return false;
                }
            }
        }
    }

    private void onClear() {
        textView.setText("");
    }

    private void onNewResults(ScanResult[] results) {
        if (results != null) {
            bufferedResults = results;

            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
            String currentDateandTime = sdf.format(new Date());

            Log.i(TAG, String.format("UI received %d interesting APs at %s", bufferedResults.length, currentDateandTime));

            textView.append(String.format("\nFound %s APs in range at %s\n", bufferedResults.length, currentDateandTime));

            if (config.getShowBSSIDs() == true) {
                for (ScanResult sr : bufferedResults) {
                    textView.append(String.format("BSSID: %s, Level: %s\n", sr.BSSID, sr.level));
                }
            }
        }
    }

    private void onSendFinished(String result) {
        if (result != null) {
            this.textView.append(result + "\n");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(serviceConnection);
    }
}
