package dev.freeneo.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.Switch;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;
import java.util.Set;
import java.util.ArrayList;

public final class MainActivity extends Activity {
    private static final int NEARBY_REQUEST = 1;
    private static final String NEO2_HOST = "192.168.2.1";
    private static final int NEO2_PORT = 9003;
    private static final long SCAN_MS = 8_000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<BluetoothDevice> seen = new HashSet<>();
    private BluetoothLeScanner scanner;
    private ScanCallback activeScan;
    private DroneConnection drone;
    private TelemetryProbe probe;
    private record Aircraft(String address, String name) {}
    private Aircraft savedAircraft, connectingAircraft;
    private Button reconnect;
    private boolean reconnectAfterPermission;
    private TextView telemetryStatus;
    private TextureView liveVideo;
    private FrameLayout previewPane;
    private ObjectOverlay objectOverlay;
    private TextView trackingStatus;
    private PreviewTracker tracker;
    private Switch trackingEnabled;
    private boolean flightSelected;
    private Surface previewSurface;
    private FrameLayout flightStage;
    private LinearLayout connectionPage, flightDeck, stickRow, cameraControls;
    private ScrollView flightScroll;
    private TextView flightStatus, previewStatus, recordingStatus, flightDetails;
    private AlertDialog flightSettings;
    private ScrollView scroll;
    private Button connectionTab, flightTab;
    private boolean airborneTabShown;
    private Switch manualArm, keepSize;
    private boolean following;
    private TextView followStatus;
    private int followRevision;
    private ManualStick leftStick, rightStick;
    private Button cameraUp, cameraDown, recordVideo, stopFollow;
    private AndroidPreview preview;
    private int right, forward, up, yaw;
    private final Runnable publishSticks = new Runnable() {
        @Override public void run() {
            if (probe == null || !manualArm.isChecked()) return;
            if (!probe.movementArmed()) {
                boolean wasFollowing = following;
                manualArm.setChecked(false);
                if (wasFollowing) showFollowStatus("Follow stopped. Tap the target again.");
                return;
            }
            boolean manual = leftStick.held() || rightStick.held();
            probe.sticks(manual ? new ManualControl(right, forward, up, yaw) : null);
            var framing = objectOverlay.framing();
            long now = SystemClock.elapsedRealtime();
            boolean videoFresh = preview != null && preview.fresh();
            long sampledAt = objectOverlay.sampledAt();
            boolean freshDetection = videoFresh && sampledAt >= 0 && now - sampledAt < 300;
            if (following && freshDetection && keepSize.isChecked() && !framing.hasSize()) framing.setSize(now);
            var correction = freshDetection ? framing.correction(now, keepSize.isChecked()) : null;
            if (following && (manual || followRevision != framing.revision())) {
                stopFollowing();
                showFollowStatus(manual ? "Follow stopped: manual stick input." : "Follow stopped: target changed or lost.");
            }
            if (following) {
                probe.follow(correction, sampledAt);
                showFollowStatus(!videoFresh ? "Follow paused: waiting for live video."
                    : correction == null ? "Follow paused: waiting for fresh target detection."
                    : "Follow requested · yaw " + correction.yaw()
                        + " · tilt " + correction.tilt() + " · forward " + correction.forward());
            }
            handler.postDelayed(this, 40);
        }
    };
    private Button liveTakeoff, liveLand, liveConfirm;
    private int probeGeneration;
    private final Runnable scanTimeout = () -> finishScan(
        seen.isEmpty() ? "No Neo 2 advertisements found." : "Select a device to connect.");
    private LinearLayout results;
    private TextView discoveryStatus;
    private boolean started;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        var root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff2f4f5);
        setContentView(root);

        var tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(dp(12), 0, dp(12), 0);
        tabs.setBackgroundColor(Color.WHITE);
        root.addView(tabs, new LinearLayout.LayoutParams(-1, dp(52)));
        connectionTab = button(tabs, "Connection", () -> showFlightTab(false));
        flightTab = button(tabs, "Flight", () -> showFlightTab(true));
        flightStatus = label(tabs, "");
        flightStatus.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        flightStatus.setTextSize(13);
        flightStatus.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));

        var common = new LinearLayout(this);
        common.setGravity(Gravity.CENTER);
        root.addView(common);
        scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        connectionPage = new LinearLayout(this);
        connectionPage.setOrientation(LinearLayout.VERTICAL);
        connectionPage.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(connectionPage);

        flightStage = new FrameLayout(this);
        flightStage.setBackgroundColor(0xfff2f4f5);
        root.addView(flightStage, new LinearLayout.LayoutParams(-1, 0, 1));
        liveVideo = new TextureView(this);
        previewPane = new FrameLayout(this);
        previewPane.setBackgroundColor(0xff11191d);
        previewPane.addView(liveVideo, new FrameLayout.LayoutParams(-1, -1));
        objectOverlay = new ObjectOverlay(this);
        previewPane.addView(objectOverlay, new FrameLayout.LayoutParams(-1, -1));
        previewStatus = new TextView(this);
        previewStatus.setText("Waiting for live video");
        previewStatus.setTextColor(Color.WHITE);
        previewStatus.setGravity(Gravity.CENTER);
        previewPane.addView(previewStatus, new FrameLayout.LayoutParams(-1, -1));
        flightStage.addView(previewPane);
        flightScroll = new ScrollView(this);
        flightScroll.setFillViewport(false);
        flightDeck = new LinearLayout(this);
        flightDeck.setOrientation(LinearLayout.VERTICAL);
        flightDeck.setPadding(dp(12), dp(4), dp(12), dp(4));
        flightScroll.addView(flightDeck);
        flightStage.addView(flightScroll);
        LinearLayout page = connectionPage;
        TextView title = label(page, "Free Neo");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label(page, "Aircraft discovery").setTextSize(20);
        discoveryStatus = label(page, "Scan for a nearby Neo 2.");
        var aircraftPreferences = getSharedPreferences("last-aircraft", MODE_PRIVATE);
        String savedAddress = aircraftPreferences.getString("address", "");
        if (BluetoothAdapter.checkBluetoothAddress(savedAddress)) {
            savedAircraft = new Aircraft(savedAddress, aircraftPreferences.getString("name", "Neo 2"));
        }
        reconnect = button(page, "Reconnect", () -> requestConnection(true));
        button(page, "Scan for Neo 2", () -> requestConnection(false));
        drone = new DroneConnection(this, handler, message -> {
            discoveryStatus.setText(message);
            if (drone.network() == null) {
                stopProbe();
                showFlightTab(false);
            } else {
                if (connectingAircraft != null) {
                    savedAircraft = connectingAircraft;
                    connectingAircraft = null;
                    aircraftPreferences.edit().putString("address", savedAircraft.address())
                        .putString("name", savedAircraft.name()).apply();
                }
                startFlightSessionIfConnected();
            }
            updateReconnectButton();
        });
        updateReconnectButton();
        button(page, "Disconnect drone", () -> {
            stopProbe();
            drone.close();
            connectingAircraft = null;
            updateReconnectButton();
            showFlightTab(false);
            discoveryStatus.setText("Drone disconnected.");
        });
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        page.addView(results);

        label(page, "Aircraft telemetry").setTextSize(20);
        telemetryStatus = label(page, "Connect to a drone to enable flight controls.");
        liveTakeoff = button(common, "Take off", () -> confirmFlight(FlightCommand.Action.TAKEOFF,
            "Take off?", "Confirm the aircraft is on the ground, the area is clear, and independent controls are ready."));
        liveLand = button(common, "Land", () -> requestFlight(FlightCommand.Action.LAND));
        liveConfirm = button(common, "Confirm landing area is clear", () -> confirmFlight(
            FlightCommand.Action.CONFIRM_LANDING, "Confirm landing?",
            "Check the landing area visually. Confirm only if the surface is safe and the area is clear."));
        addManualControls(flightDeck);
        showFlightTab(false);
        renderLiveControls(LiveFlight.Controls.DISABLED);
    }

    private void showFlightTab(boolean selected) {
        flightSelected = selected;
        // Keep the decoder surface visible at one pixel while on the connection page.
        if (!selected) stopTracking();
        if (!selected && manualArm != null) manualArm.setChecked(false);
        scroll.setVisibility(selected ? View.GONE : View.VISIBLE);
        flightStage.setAlpha(selected ? 1f : 0f);
        flightStage.getLayoutParams().height = selected ? 0 : 1;
        ((LinearLayout.LayoutParams) flightStage.getLayoutParams()).weight = selected ? 1 : 0;
        flightStage.requestLayout();
        flightDeck.setVisibility(selected ? View.VISIBLE : View.GONE);
        cameraControls.setVisibility(selected ? View.VISIBLE : View.GONE);
        flightStatus.setVisibility(selected ? View.VISIBLE : View.GONE);
        flightStage.setImportantForAccessibility(selected ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        connectionTab.setSelected(!selected);
        flightTab.setSelected(selected);
        updateFlightLayout(getResources().getConfiguration());
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        manualArm.setChecked(false);
        updateFlightLayout(configuration);
    }

    private void updateFlightLayout(Configuration configuration) {
        boolean landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE;
        int portraitHeight = dp(Math.min(configuration.screenWidthDp * 9 / 16, configuration.screenHeightDp / 3));
        previewPane.setLayoutParams(new FrameLayout.LayoutParams(-1,
            !flightSelected || landscape ? -1 : portraitHeight));
        if (landscape) {
            if (flightDeck.getParent() == flightScroll) {
                flightScroll.removeView(flightDeck);
                flightDeck.removeView(cameraControls);
                flightStage.addView(flightDeck, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
                var camera = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
                camera.setMargins(dp(8), dp(4), dp(8), 0);
                flightStage.addView(cameraControls, camera);
            }
            flightScroll.setVisibility(View.GONE);
        } else {
            if (flightDeck.getParent() == flightStage) {
                flightStage.removeView(flightDeck);
                flightStage.removeView(cameraControls);
                flightDeck.addView(cameraControls, 1);
                flightScroll.addView(flightDeck);
            }
            var deck = new FrameLayout.LayoutParams(-1, -1);
            deck.topMargin = portraitHeight;
            flightScroll.setLayoutParams(deck);
            flightScroll.setVisibility(flightSelected ? View.VISIBLE : View.GONE);
        }
        int stickHeight = dp(landscape ? Math.max(92, Math.min(172, configuration.screenHeightDp - 202))
            : Math.min(210, configuration.screenWidthDp / 2));
        int stickWidth = landscape ? stickHeight : 0;
        View spacer = stickRow.getChildAt(1);
        spacer.setVisibility(landscape ? View.VISIBLE : View.GONE);
        for (ManualStick stick : new ManualStick[] {leftStick, rightStick}) {
            var params = (LinearLayout.LayoutParams) stick.getLayoutParams();
            params.height = stickHeight;
            params.width = stickWidth;
            params.weight = landscape ? 0 : 1;
            stick.setOverVideo(landscape);
        }
        stickRow.requestLayout();
    }

    private void startFlightSessionIfConnected() {
        if (started && drone.network() != null && probe == null && liveVideo.isAvailable()) {
            readTelemetry(TelemetryProbe.Mode.LIVE);
        }
    }

    private void attachPreview() {
        if (probe == null || preview != null || !liveVideo.isAvailable()) return;
        previewSurface = new Surface(liveVideo.getSurfaceTexture());
        preview = new AndroidPreview(previewSurface);
        probe.setPreview(preview);
    }

    private void stopTracking() {
        stopFollowing();
        if (tracker != null) tracker.close();
        tracker = null;
        if (trackingStatus != null) trackingStatus.setText(trackingEnabled.isChecked()
            ? "Waiting for video to track objects." : "Object detection off.");
    }

    private void detachPreview() {
        stopTracking();
        if (probe != null) probe.setPreview(null);
        if (preview != null) preview.close();
        preview = null;
        if (previewSurface != null) previewSurface.release();
        previewSurface = null;
    }

    private void addManualControls(LinearLayout page) {
        liveVideo.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                attachPreview();
                startFlightSessionIfConnected();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}
            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                if (!flightSelected || !trackingEnabled.isChecked() || preview == null || !preview.fresh()) return;
                if (tracker == null) tracker = new PreviewTracker(liveVideo, objectOverlay, trackingStatus);
                tracker.sample();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                manualArm.setChecked(false);
                detachPreview();
                return true;
            }
        });
        var options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(dp(20), dp(8), dp(20), dp(16));
        flightDetails = label(options, telemetryStatus.getText().toString());
        var optionsScroll = new ScrollView(this);
        optionsScroll.addView(options);
        flightSettings = new AlertDialog.Builder(this).setTitle("Flight controls")
            .setView(optionsScroll).setPositiveButton("Done", null).create();
        trackingEnabled = new Switch(this);
        trackingEnabled.setText("Object detection");
        options.addView(trackingEnabled);
        trackingStatus = label(options, "Object detection off.");
        trackingEnabled.setOnCheckedChangeListener((view, enabled) -> {
            if (enabled) trackingStatus.setText("Waiting for video to track objects.");
            else stopTracking();
        });
        objectOverlay.setStatusView(label(options, ""));
        keepSize = new Switch(this);
        keepSize.setText("Keep apparent size");
        keepSize.setChecked(true);
        options.addView(keepSize);
        label(options, "With object detection on, tap a target in the preview to follow it.");

        objectOverlay.setOnTargetSelected(this::startFollowing);
        manualArm = new Switch(this);
        manualArm.setText("Arm manual movement");
        var modeBar = new LinearLayout(this);
        modeBar.setGravity(Gravity.CENTER);
        page.addView(modeBar);
        manualArm.setBackground(rounded(0xfffafbfb, 24));
        manualArm.setPadding(dp(10), 0, dp(10), 0);
        modeBar.addView(manualArm, new LinearLayout.LayoutParams(-2, dp(44)));
        stopFollow = button(modeBar, "Controls", () -> {
            if (following) stopFollowing();
            else {
                flightDetails.setText(telemetryStatus.getText());
                flightSettings.show();
            }
        });
        label(options, "Arm only with the aircraft in sight and independent controls ready.");
        manualArm.setOnCheckedChangeListener((view, checked) -> {
            handler.removeCallbacks(publishSticks);
            if (checked) {
                if (probe == null || !probe.armMovement(true)) {
                    manualArm.setChecked(false);
                    return;
                }
                leftStick.setEnabled(true);
                rightStick.setEnabled(true);
                publishSticks.run();
            } else {
                stopFollowing();
                leftStick.release();
                rightStick.release();
                leftStick.setEnabled(false);
                rightStick.setEnabled(false);
                if (probe != null) probe.armMovement(false);
            }
        });
        cameraControls = new LinearLayout(this);
        cameraControls.setOrientation(LinearLayout.VERTICAL);
        page.addView(cameraControls);
        var cameraBar = new LinearLayout(this);
        cameraBar.setGravity(Gravity.CENTER);
        cameraControls.addView(cameraBar);
        cameraUp = button(cameraBar, "Camera up", () -> { if (probe != null) probe.camera(0); });
        recordVideo = button(cameraBar, "●", () -> {
            if (probe != null) probe.toggleRecording();
        });
        recordVideo.setContentDescription("Record on drone");
        recordVideo.setBackground(rounded(0xffc71739, 32));
        recordVideo.setTextColor(Color.WHITE);
        recordVideo.setTextSize(22);
        recordVideo.setMinWidth(0);
        recordVideo.setMinimumWidth(0);
        recordVideo.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(52)));
        cameraDown = button(cameraBar, "Camera down", () -> { if (probe != null) probe.camera(-30); });
        recordingStatus = label(cameraControls, "");
        recordingStatus.setGravity(Gravity.CENTER);
        recordingStatus.setTextSize(12);
        recordingStatus.setBackground(rounded(0xfffafbfb, 16));
        var recordingLine = new LinearLayout.LayoutParams(-2, -2);
        recordingLine.gravity = Gravity.CENTER;
        recordingStatus.setPadding(dp(8), 0, dp(8), 0);
        recordingStatus.setLayoutParams(recordingLine);

        followStatus = label(page, "");
        followStatus.setGravity(Gravity.CENTER);
        followStatus.setTextSize(12);
        followStatus.setSingleLine(true);
        followStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        followStatus.setMinHeight(dp(20));
        followStatus.setBackground(rounded(0xfffafbfb, 16));
        followStatus.setVisibility(View.INVISIBLE);
        stickRow = new LinearLayout(this);
        page.addView(stickRow);
        leftStick = new ManualStick(this, "Climb, descend, and rotate",
            "↑ Climb", "↓ Descend", "‹ Rotate", "Rotate ›", (x, y) -> { yaw = x; up = y; });
        rightStick = new ManualStick(this, "Move forward, backward, left, and right",
            "↑ Forward", "↓ Backward", "‹ Left", "Right ›", (x, y) -> { right = x; forward = y; });
        stickRow.addView(leftStick, new LinearLayout.LayoutParams(0, 0, 1));
        stickRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        stickRow.addView(rightStick, new LinearLayout.LayoutParams(0, 0, 1));
        leftStick.setEnabled(false);
        rightStick.setEnabled(false);
    }

    private void startFollowing() {
        stopFollowing();
        if (probe == null || preview == null || !preview.fresh() || !manualArm.isEnabled()
                || leftStick.held() || rightStick.held()) {
            showFollowStatus("Follow unavailable. Tap the target again when movement is ready.");
            return;
        }
        manualArm.setChecked(true);
        if (!probe.movementArmed()) {
            showFollowStatus("Follow unavailable. Tap the target again when movement is ready.");
            return;
        }
        followRevision = objectOverlay.framing().revision();
        following = true;
        probe.follow(null, -1);
        stopFollow.setText("Stop follow");
        showFollowStatus("Following selected object.");
    }

    private void stopFollowing() {
        following = false;
        if (stopFollow != null) stopFollow.setText("Controls");
        if (probe != null) probe.stopFollow();
        if (followStatus != null) followStatus.setVisibility(View.INVISIBLE);
    }

    private void showFollowStatus(String status) {
        followStatus.setText(status);
        followStatus.setVisibility(View.VISIBLE);
    }

    private void readTelemetry(TelemetryProbe.Mode mode) {
        var network = drone.network();
        if (network == null) {
            telemetryStatus.setText("Connect to drone Wi-Fi first.");
            return;
        }
        stopProbe();
        var connectivity = getSystemService(ConnectivityManager.class);
        var properties = connectivity.getLinkProperties(network);
        if (properties == null) {
            telemetryStatus.setText("Drone Wi-Fi settings unavailable. Reconnect to retry.");
            return;
        }
        var routes = properties.getRoutes();
        InetAddress expected = InetAddresses.parseNumericAddress(NEO2_HOST);
        InetAddress host = expected;
        if (routes.stream().noneMatch(route -> !route.hasGateway() && route.matches(expected))) {
            var gateways = routes.stream().filter(route -> route.isDefaultRoute() && route.hasGateway())
                .map(route -> route.getGateway()).filter(address -> address instanceof Inet4Address
                    && !address.isAnyLocalAddress() && !address.isLoopbackAddress() && !address.isMulticastAddress())
                .filter(address -> routes.stream().anyMatch(route -> !route.hasGateway() && route.matches(address)))
                .distinct().collect(java.util.stream.Collectors.toList());
            if (gateways.size() != 1) {
                telemetryStatus.setText("Could not locate the aircraft on its Wi-Fi. Reconnect to retry.");
                return;
            }
            host = gateways.get(0);
        }
        int generation = probeGeneration;
        telemetryStatus.setText(switch (mode) {
            case READ -> "Opening a read-only DJI session…";
            case CHECK_SERVICE -> "Checking DJI Wi-Fi service activation…";
            case LIVE -> "Opening a DJI flight session…";
        });
        var identities = getSharedPreferences("wifi-session", MODE_PRIVATE);
        String identity = identities.getString("identity", null);
        if (identity == null) {
            identity = UUID.randomUUID().toString().replace("-", "");
            identities.edit().putString("identity", identity).apply();
        }
        probe = new TelemetryProbe(network, host, NEO2_PORT, identity, mode, message -> handler.post(() -> {
            if (generation == probeGeneration) {
                telemetryStatus.setText(message);
                flightDetails.setText(message);
            }
        }), controls -> handler.post(() -> {
            if (generation == probeGeneration) renderLiveControls(controls);
        }), null);
        attachPreview();
        probe.start();
    }

    private void renderLiveControls(LiveFlight.Controls controls) {
        if (controls.airborne() && !airborneTabShown) {
            airborneTabShown = true;
            showFlightTab(true);
        }
        if (controls.takeoff()) airborneTabShown = false;
        liveTakeoff.setEnabled(controls.takeoff());
        liveLand.setEnabled(controls.land());
        liveConfirm.setEnabled(controls.confirm());
        boolean videoFresh = preview != null && preview.fresh();
        boolean allowArm = controls.land() && videoFresh && probe != null && probe.movementReady();
        boolean preserveFollow = following && probe != null && probe.movementArmed();
        if (!allowArm && !preserveFollow) {
            boolean wasFollowing = following;
            manualArm.setChecked(false);
            if (wasFollowing) showFollowStatus("Follow stopped. Tap the target again.");
        }
        manualArm.setEnabled(allowArm || preserveFollow);
        recordVideo.setEnabled(probe != null && probe.recordingReady());
        String recordState = probe == null ? "Camera recording unavailable" : probe.recordingStatus();
        recordVideo.setContentDescription(recordState);
        recordVideo.setText(switch (recordState) {
            case "Stop recording", "Stopping recording…", "Retry recording stop",
                "Recording uncertain · Send stop" -> "■";
            default -> "●";
        });
        recordVideo.setAlpha(recordVideo.isEnabled() ? 1f : .45f);
        recordingStatus.setText(recordState);
        previewStatus.setVisibility(videoFresh ? View.GONE : View.VISIBLE);
        liveTakeoff.setVisibility(controls.takeoff() ? View.VISIBLE : View.GONE);
        liveLand.setVisibility(controls.land() ? View.VISIBLE : View.GONE);
        liveConfirm.setVisibility(controls.confirm() ? View.VISIBLE : View.GONE);
        String state = controls.confirm() ? "Landing confirmation"
            : controls.airborne() ? "In flight"
            : controls.takeoff() ? "Ready for takeoff" : "Flight state unavailable";
        int battery = probe == null ? -1 : probe.batteryPercent();
        flightStatus.setText(battery < 0 ? state : state + "  ·  " + battery + "%");
        cameraUp.setEnabled(videoFresh);
        cameraDown.setEnabled(videoFresh);
    }

    private void requestFlight(FlightCommand.Action action) {
        if (probe != null) probe.request(action);
    }

    private void confirmFlight(FlightCommand.Action action, String title, String message) {
        int generation = probeGeneration;
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Confirm", (dialog, which) -> {
                if (generation == probeGeneration) requestFlight(action);
            }).show();
    }

    private void stopProbe() {
        probeGeneration++;
        airborneTabShown = false;
        detachPreview();
        if (probe != null) {
            probe.close();
            probe = null;
        }
        renderLiveControls(LiveFlight.Controls.DISABLED);
        telemetryStatus.setText("Connect to a drone to enable flight controls.");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int color, int radius) {
        var shape = new GradientDrawable();
        shape.setCornerRadius(dp(radius));
        shape.setColor(color);
        return shape;
    }

    private TextView label(LinearLayout parent, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(0xff213033);
        view.setTextSize(15);
        parent.addView(view);
        return view;
    }

    private Button button(LinearLayout parent, String text, Runnable action) {
        Button view = new Button(this);
        view.setText(text);
        view.setAllCaps(false);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(new ColorStateList(
            new int[][] {{-android.R.attr.state_enabled}, {android.R.attr.state_selected}, {}},
            new int[] {0xff606b70, 0xff006353, 0xff213033}));
        view.setBackground(new android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(0x22006454), rounded(0xffe6edec, 18), null));
        view.setPadding(dp(10), 0, dp(10), 0);
        var layout = new LinearLayout.LayoutParams(-2, dp(44));
        layout.setMargins(dp(3), dp(3), dp(3), dp(3));
        view.setLayoutParams(layout);
        view.setOnClickListener(ignored -> action.run());
        parent.addView(view);
        return view;
    }

    private String[] missingPermissions() {
        var needed = new ArrayList<String>();
        for (String permission : new String[] {Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES}) {
            if (permission.equals(Manifest.permission.NEARBY_WIFI_DEVICES) && Build.VERSION.SDK_INT < 33) continue;
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) needed.add(permission);
        }
        if (Build.VERSION.SDK_INT < 33 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return needed.toArray(new String[0]);
    }

    private void updateReconnectButton() {
        reconnect.setVisibility(savedAircraft == null ? View.GONE : View.VISIBLE);
        if (savedAircraft != null) reconnect.setText("Reconnect to " + savedAircraft.name());
        reconnect.setEnabled(drone.network() == null);
    }

    private void requestConnection(boolean useSavedAircraft) {
        String[] missing = missingPermissions();
        if (missing.length == 0) {
            if (useSavedAircraft) reconnectToSavedAircraft();
            else startScan();
        } else {
            reconnectAfterPermission = useSavedAircraft;
            requestPermissions(missing, NEARBY_REQUEST);
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != NEARBY_REQUEST || !started) return;
        if (missingPermissions().length == 0) requestConnection(reconnectAfterPermission);
        else discoveryStatus.setText("Allow Bluetooth and Wi-Fi permissions to discover and connect.");
    }

    private void reconnectToSavedAircraft() {
        if (savedAircraft == null || drone.network() != null) return;
        var manager = getSystemService(BluetoothManager.class);
        var adapter = manager == null ? null : manager.getAdapter();
        try {
            if (adapter != null && adapter.isEnabled()) {
                connectAircraft(adapter.getRemoteDevice(savedAircraft.address()), savedAircraft);
                return;
            }
        } catch (IllegalArgumentException | SecurityException ignored) {
            // The saved address or Bluetooth permission may no longer be usable.
        }
        discoveryStatus.setText("Reconnect unavailable. Check Bluetooth or scan for the drone again.");
    }

    private void connectAircraft(BluetoothDevice device, Aircraft aircraft) {
        stopScan();
        connectingAircraft = aircraft;
        drone.connect(device);
    }

    private void startScan() {
        stopScan();
        stopProbe();
        drone.close();
        connectingAircraft = null;
        updateReconnectButton();
        results.removeAllViews();
        seen.clear();
        var manager = getSystemService(BluetoothManager.class);
        var adapter = manager == null ? null : manager.getAdapter();
        try {
            scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
            if (scanner == null) {
                discoveryStatus.setText("Bluetooth LE unavailable. Check that Bluetooth is on.");
                return;
            }
            activeScan = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult result) {
                    runOnUiThread(() -> {
                        if (activeScan == this) showResult(result);
                    });
                }
                @Override public void onScanFailed(int error) {
                    runOnUiThread(() -> {
                        if (activeScan == this) finishScan("Bluetooth scan failed. Code " + error);
                    });
                }
            };
            discoveryStatus.setText("Scanning for Neo 2…");
            scanner.startScan(activeScan);
            handler.postDelayed(scanTimeout, SCAN_MS);
        } catch (SecurityException | IllegalStateException error) {
            finishScan("Bluetooth scan unavailable. Check Bluetooth and Nearby devices access.");
        }
    }

    private void showResult(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        if (record == null) return;
        String name = record.getDeviceName();
        boolean serviceAdvertised = record.getServiceUuids() != null
            && record.getServiceUuids().contains(new ParcelUuid(DroneConnection.SERVICE));
        boolean neoName = name != null && name.toUpperCase(Locale.ROOT).startsWith("DJI-NEO2-");
        BluetoothDevice device = result.getDevice();
        if ((!neoName && !serviceAdvertised) || !seen.add(device)) return;
        String displayName = neoName ? name : "Possible Neo 2 (" + seen.size() + ")";
        var aircraft = new Aircraft(device.getAddress(), neoName ? name : "Neo 2");
        button(results, "Connect to " + displayName, () -> connectAircraft(device, aircraft));
    }

    private void stopScan() {
        handler.removeCallbacks(scanTimeout);
        ScanCallback previous = activeScan;
        activeScan = null;
        if (scanner != null && previous != null) {
            try {
                scanner.stopScan(previous);
            } catch (SecurityException | IllegalStateException ignored) {
                // Bluetooth or its permission can disappear during the scan.
            }
        }
        scanner = null;
    }

    private void finishScan(String message) {
        stopScan();
        discoveryStatus.setText(message);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused && manualArm != null && manualArm.isChecked()) manualArm.setChecked(false);
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        startFlightSessionIfConnected();
    }

    @Override protected void onStop() {
        started = false;
        stopProbe();
        if (activeScan != null) discoveryStatus.setText("Scan stopped. Scan again to continue.");
        stopScan();
        super.onStop();
    }

    @Override protected void onDestroy() {
        drone.close();
        super.onDestroy();
    }
}
