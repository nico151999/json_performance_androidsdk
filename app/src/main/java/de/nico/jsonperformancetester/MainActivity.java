package de.nico.jsonperformancetester;

import androidx.annotation.UiThread;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getName();

    private String[] mFiles;
    private Map<String, TextView> mViews;
    private Map<String, String> mFileStrings;
    private Map<String, List<Pair<Long, Long>>> mFileResults;

    private FloatingActionButton mFAB;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mFAB = findViewById(R.id.floating_action_button);

        mFileStrings = new HashMap<>();
        mViews = new HashMap<>();
        mFileResults = new HashMap<>();

        AssetManager am = getApplicationContext().getAssets();
        try {
            String assetPrefix = "input";
            mFiles = am.list(assetPrefix);

            LinearLayout container = findViewById(R.id.data_container);

            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            for (String file : mFiles) {
                InputStream is = am.open(assetPrefix + "/" + file);
                byte[] targetArray = new byte[is.available()];
                is.read(targetArray);
                mFileStrings.put(file, new String(targetArray));

                mFileResults.put(file, new ArrayList<>());

                TextView headLine = new TextView(this);
                headLine.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
                headLine.setLayoutParams(layoutParams);
                headLine.setText(getString(R.string.headline_text, file));
                container.addView(headLine);
                mViews.put(file, headLine);

                TextView valueView = new TextView(this);
                valueView.setTypeface(valueView.getTypeface(), Typeface.BOLD);
                valueView.setTextSize(20);
                valueView.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
                valueView.setLayoutParams(layoutParams);
                valueView.setText(getString(R.string.waiting));
                container.addView(valueView);
                mViews.put(file, valueView);
            }
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, e.getMessage());
            finish();
            return;
        }
        am.close();

        mFAB.setOnClickListener(this::onClickFAB);
    }

    private void onClickFAB(View view) {
        toggleFAB(false);
        startPerformanceTest();
    }

    @UiThread
    private void assignValuesToView() {
        mViews.forEach((file, view) -> {
            int parsingSum = 0;
            int creationSum = 0;
            List<Pair<Long, Long>> measurements = mFileResults.get(file);
            for (Pair<Long, Long> measurement : measurements) {
                parsingSum += measurement.first;
                creationSum += measurement.second;
            }
            int itemCount = measurements.size();
            long parsingAverage = parsingSum / itemCount;
            long creationAverage = creationSum / itemCount;
            view.setText(getString(R.string.parsing_creation, parsingAverage, creationAverage));
        });
    }

    private void startPerformanceTest() {
        new Thread() {
            @Override
            public void run() {
                try {
                    int iterations = 5;
                    Map<String, Object> jsonMap;
                    while (iterations-- != 0) {
                        for (String file : mFiles) {
                            String json = mFileStrings.get(file);
                            long startTimestamp = System.nanoTime();
                            jsonMap = jsonObjectToMap(new JSONObject(json));
                            long parsedTimestamp = System.nanoTime();
                            new JSONObject(jsonMap).toString();
                            long endTimestamp = System.nanoTime();
                            long parsingTime = (parsedTimestamp - startTimestamp) / 1000;
                            long creationTime = (endTimestamp - parsedTimestamp) / 1000;
                            Log.i(TAG, String.format("Parsing %s took %sµs", file, parsingTime));
                            Log.i(TAG, String.format("Creating %s took %sµs", file, creationTime));
                            mFileResults.get(file).add(new Pair<>(parsingTime, creationTime));
                        }
                    }
                    runOnUiThread(() -> {
                        assignValuesToView();
                        toggleFAB(true);
                    });
                } catch (JSONException e) {
                    e.printStackTrace();
                    runOnUiThread(() -> {
                        Toast.makeText(
                                MainActivity.this,
                                R.string.json_error,
                                Toast.LENGTH_LONG
                        ).show();
                        toggleFAB(true);
                    });
                }
            }
        }.start();
    }

    private Map<String, Object> jsonObjectToMap(JSONObject object) throws JSONException {
        Map<String, Object> map = new HashMap<>();
        for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
            String key = keys.next();
            Object value = object.get(key);
            if (value instanceof JSONArray) {
                value = jsonArrayToList((JSONArray) value);
            } else if (value instanceof JSONObject) {
                value = jsonObjectToMap((JSONObject) value);
            }
            map.put(key, value);
        }
        return map;
    }

    private List<Object> jsonArrayToList(JSONArray array) throws JSONException {
        List<Object> list = new ArrayList<>();
        for(int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if(value instanceof JSONArray) {
                value = jsonArrayToList((JSONArray) value);
            } else if(value instanceof JSONObject) {
                value = jsonObjectToMap((JSONObject) value);
            }
            list.add(value);
        }
        return list;
    }

    @UiThread
    private void toggleFAB(boolean enable) {
        mFAB.setEnabled(enable);
        mFAB.setImageResource(
                enable ? android.R.drawable.ic_media_play : android.R.drawable.ic_menu_rotate
        );
        mFAB.setBackgroundTintList(
                ColorStateList.valueOf(
                        ContextCompat.getColor(
                                this,
                                enable ?
                                        R.color.colorAccent :
                                        android.R.color.holo_red_light
                        )
                )
        );
    }
}
