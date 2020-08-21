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
import com.google.firebase.FirebaseApp;
import com.google.firebase.functions.FirebaseFunctions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getName();

    private String[] mFiles;
    private Map<String, TextView> mViews;
    private Map<String, Map<String, Object>> mFileMaps;
    private Map<String, List<Pair<Long, Long>>> mFileResults;

    private FloatingActionButton mFAB;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mFAB = findViewById(R.id.floating_action_button);

        mFileMaps = new HashMap<>();
        mViews = new HashMap<>();
        mFileResults = new HashMap<>();

        FirebaseApp.initializeApp(MainActivity.this);

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
                mFileMaps.put(file, jsonObjectToMap(new JSONObject(new String(targetArray))));

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
        } catch (IOException | JSONException e) {
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
                creationSum += measurement.first;
                parsingSum += measurement.second;
            }
            int itemCount = measurements.size();
            long parsingAverage = parsingSum / itemCount;
            long creationAverage = creationSum / itemCount;
            view.setText(
                    getString(
                            R.string.parsing_creation,
                            creationAverage,
                            parsingAverage
                    )
            );
        });
    }

    private void startPerformanceTest() {
        new Thread() {
            @Override
            public void run() {
                try {
                    FirebaseFunctions functions = FirebaseFunctions.getInstance();
                    Field serializerField = functions.getClass().getDeclaredField("serializer");
                    serializerField.setAccessible(true);
                    Object serializer = serializerField.get(functions);
                    Method encodeMethod = serializer.getClass().getDeclaredMethod("encode", Object.class);
                    encodeMethod.setAccessible(true);
                    Method decodeMethod = serializer.getClass().getDeclaredMethod("decode", Object.class);
                    encodeMethod.setAccessible(true);
                    int iterations = 50;
                    String createdJson;
                    while (iterations-- != 0) {
                        for (String file : mFiles) {
                            Map<String, Object> json = mFileMaps.get(file);
                            long startTimestamp = System.nanoTime();
                            createdJson = createJson(json, encodeMethod, serializer);
                            long createdTimestamp = System.nanoTime();
                            parseJson(createdJson, decodeMethod, serializer);
                            long endTimestamp = System.nanoTime();
                            long creationTime = (createdTimestamp - startTimestamp) / 1000;
                            long parsingTime = (endTimestamp - createdTimestamp) / 1000;
                            Log.i(TAG, String.format("Creation %s took %sµs", file, creationTime));
                            Log.i(TAG, String.format("Parsing %s took %sµs", file, parsingTime));
                            mFileResults.get(file).add(new Pair<>(creationTime, parsingTime));
                        }
                    }
                    runOnUiThread(() -> {
                        assignValuesToView();
                        toggleFAB(true);
                    });
                } catch (Exception e) {
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

    private String createJson(Object parsedJson, Method encodeMethod, Object serializer) throws InvocationTargetException, IllegalAccessException {
        Map<String, Object> body = new HashMap<>();
        Object encoded = encodeMethod.invoke(serializer, parsedJson);
        body.put("data", encoded);
        JSONObject bodyJSON = new JSONObject(body);
        return bodyJSON.toString();
    }

    private Map<String, Object> parseJson(String json, Method decodeMethod, Object serializer) throws JSONException, InvocationTargetException, IllegalAccessException {
        JSONObject bodyJSON;
        bodyJSON = new JSONObject(json);

        Object dataJSON = bodyJSON.opt("data");
        if (dataJSON == null) {
            dataJSON = bodyJSON.opt("result");
        }

        if (dataJSON == null) {
            throw new JSONException("Json has incompatible format");
        } else {
            Object decoded = decodeMethod.invoke(serializer, dataJSON);
            if (decoded instanceof Map) {
                return (Map<String, Object>) decoded;
            } else {
                throw new JSONException("Json has to have a format that can be converted to a Map");
            }
        }
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