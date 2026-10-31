package com.ivy.perfprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class AnimationProbe extends Instrumentation {
    private Activity activity;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    private int listenerCount() throws Exception {
        final int[] result = {-1};
        final Throwable[] error = {null};
        runOnMainSync(() -> {
            try {
                ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
                Field field = ViewTreeObserver.class.getDeclaredField("mOnGlobalLayoutListeners");
                field.setAccessible(true);
                Object listeners = field.get(observer);
                if (listeners == null) { result[0] = 0; return; }
                Method size = listeners.getClass().getDeclaredMethod("size");
                size.setAccessible(true);
                result[0] = (Integer) size.invoke(listeners);
            } catch (Throwable t) { error[0] = t; }
        });
        if (error[0] != null) throw new RuntimeException(error[0]);
        return result[0];
    }

    private void tap(float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0);
        sendPointerSync(down); sendPointerSync(up); down.recycle(); up.recycle();
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String needle) {
        if (node == null) return null;
        if ((node.getText() != null && node.getText().toString().equalsIgnoreCase(needle)) ||
            (node.getContentDescription() != null && node.getContentDescription().toString().equalsIgnoreCase(needle))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = find(node.getChild(i), needle);
            if (found != null) return found;
        }
        return null;
    }

    private void clickText(String text) {
        AccessibilityNodeInfo node = null;
        for (int i = 0; i < 30 && node == null; i++) {
            node = find(getUiAutomation().getRootInActiveWindow(), text);
            if (node == null) SystemClock.sleep(100);
        }
        if (node == null) {
            StringBuilder tree = new StringBuilder();
            describe(getUiAutomation().getRootInActiveWindow(), tree);
            throw new IllegalStateException("Missing " + text + ": " + tree);
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds); tap(bounds.centerX(), bounds.centerY());
        }
    }

    private boolean home(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.getText() != null && node.getText().toString().equals("Home") &&
            "com.ivy.wallet.debug".contentEquals(node.getPackageName())) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (home(node.getChild(i))) return true;
        return false;
    }

    private boolean waitHome() {
        for (int i = 0; i < 20; i++) {
            if (home(getUiAutomation().getRootInActiveWindow())) return true;
            SystemClock.sleep(100);
        }
        return false;
    }

    private void describe(AccessibilityNodeInfo node, StringBuilder out) {
        if (node == null) return;
        if (node.getText() != null) out.append(node.getText()).append("; ");
        if (node.getContentDescription() != null) out.append('[').append(node.getContentDescription()).append("] ");
        for (int i = 0; i < node.getChildCount(); i++) describe(node.getChild(i), out);
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Intent intent = new Intent().setClassName("com.ivy.wallet.debug", "com.ivy.wallet.RootActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity = startActivitySync(intent);
            SystemClock.sleep(4000);
            int initial = listenerCount();
            result.putInt("listeners_initial", initial);
            StringBuilder counts = new StringBuilder().append(initial);
            for (int i = 0; i < 12; i++) {
                clickText("circle button"); SystemClock.sleep(700);
                clickText("ADD EXPENSE"); SystemClock.sleep(1000);
                boolean editorOpened = false;
                for (int j = 0; j < 30; j++) {
                    AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
                    if (!home(root) && find(root, "Account") != null && find(root, "Enter") != null) {
                        editorOpened = true;
                        break;
                    }
                    SystemClock.sleep(100);
                }
                if (!editorOpened) throw new IllegalStateException("Expense amount editor did not open");
                for (int j = 0; j < 4; j++) {
                    if (home(getUiAutomation().getRootInActiveWindow())) break;
                    sendKeyDownUpSync(4);
                    if (waitHome()) break;
                }
                if (!home(getUiAutomation().getRootInActiveWindow())) throw new IllegalStateException("Did not return Home");
                counts.append(',').append(listenerCount());
                result.putInt("completed_cycles", i+1);
                Bundle status = new Bundle(); status.putInt("cycle", i+1); status.putInt("listeners", listenerCount());
                sendStatus(0, status);
            }
            result.putInt("listeners_initial", initial);
            result.putInt("listeners_final", listenerCount());
            result.putString("listener_counts", counts.toString());
            result.putString("result", "12 expense editor open/close cycles completed");
            finish(-1, result);
        } catch (Throwable t) {
            result.putString("error", t.toString()); finish(0, result);
        }
    }
}
