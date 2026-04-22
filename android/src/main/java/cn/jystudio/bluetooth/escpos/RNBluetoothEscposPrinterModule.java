package cn.jystudio.bluetooth.escpos;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.util.Base64;
import android.util.Log;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.os.Build;
import android.view.View;
import android.graphics.Canvas;
import android.graphics.Color;
import cn.jystudio.bluetooth.BluetoothService;
import cn.jystudio.bluetooth.BluetoothServiceStateObserver;
import cn.jystudio.bluetooth.escpos.command.sdk.Command;
import cn.jystudio.bluetooth.escpos.command.sdk.PrintPicture;
import cn.jystudio.bluetooth.escpos.command.sdk.PrinterCommand;
import com.facebook.react.bridge.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.*;

public class RNBluetoothEscposPrinterModule extends ReactContextBaseJavaModule
        implements BluetoothServiceStateObserver {
    private static final String TAG = "BluetoothEscposPrinter";

    public static final int WIDTH_58 = 384;
    public static final int WIDTH_80 = 576;

    private static final int CONTENT_SIDE_MARGIN = 20;

    private final ReactApplicationContext reactContext;
    private int deviceWidth = WIDTH_58;
    private BluetoothService mService;

    public RNBluetoothEscposPrinterModule(ReactApplicationContext reactContext,
                                          BluetoothService bluetoothService) {
        super(reactContext);
        this.reactContext = reactContext;
        this.mService = bluetoothService;
        this.mService.addStateObserver(this);
    }

    @Override
    public String getName() {
        return "BluetoothEscposPrinter";
    }

    @Override
    public @Nullable Map<String, Object> getConstants() {
        Map<String, Object> constants = new HashMap<>();
        constants.put("width58", WIDTH_58);
        constants.put("width80", WIDTH_80);
        return constants;
    }

    @ReactMethod
    public void printerInit(final Promise promise) {
        if (sendDataByte(PrinterCommand.POS_Set_PrtInit())) promise.resolve(null);
        else promise.reject("COMMAND_NOT_SEND");
    }

    @ReactMethod
    public void printAndFeed(int feed, final Promise promise) {
        if (sendDataByte(PrinterCommand.POS_Set_PrtAndFeedPaper(feed))) promise.resolve(null);
        else promise.reject("COMMAND_NOT_SEND");
    }

    @ReactMethod
    public void printerLeftSpace(int sp, final Promise promise) {
        if (sendDataByte(PrinterCommand.POS_Set_LeftSP(sp))) promise.resolve(null);
        else promise.reject("COMMAND_NOT_SEND");
    }

    @ReactMethod
    public void printerLineSpace(int sp, final Promise promise) {
        byte[] command = PrinterCommand.POS_Set_DefLineSpace();
        if (sp > 0) command = PrinterCommand.POS_Set_LineSpace(sp);
        if (command == null || !sendDataByte(command)) promise.reject("COMMAND_NOT_SEND");
        else promise.resolve(null);
    }

    @ReactMethod
    public void printerUnderLine(int line, final Promise promise) {
        if (sendDataByte(PrinterCommand.POS_Set_UnderLine(line))) promise.resolve(null);
        else promise.reject("COMMAND_NOT_SEND");
    }

    @ReactMethod
    public void printerAlign(int align, final Promise promise) {
        Log.d(TAG, "Align:" + align);
        if (sendDataByte(PrinterCommand.POS_S_Align(align))) promise.resolve(null);
        else promise.reject("COMMAND_NOT_SEND");
    }

    @ReactMethod
    public void printText(String text, @Nullable ReadableMap options, final Promise promise) {
        try {
            if (text == null || text.trim().isEmpty()) { promise.resolve(null); return; }

            String encoding = "GBK";
            int codepage = 0, widthTimes = 0, heigthTimes = 0, fonttype = 0;
            if (options != null) {
                encoding   = options.hasKey("encoding")    ? options.getString("encoding") : "GBK";
                codepage   = options.hasKey("codepage")    ? options.getInt("codepage")    : 0;
                widthTimes = options.hasKey("widthtimes")  ? options.getInt("widthtimes")  : 0;
                heigthTimes= options.hasKey("heigthtimes") ? options.getInt("heigthtimes") : 0;
                fonttype   = options.hasKey("fonttype")    ? options.getInt("fonttype")    : 0;
            }

            sendDataByte(PrinterCommand.POS_Set_Bold(fonttype > 0 ? 1 : 0));
            if (fonttype == 0) sendDataByte(Command.ESC_ExclamationMark);

            // ── UNIFIED BITMAP PATH ──────────────────────────────────────────────────
            Bitmap bmp = null;
            try { bmp = renderTextToBitmap(text, deviceWidth, false, fonttype > 0); }
            catch (Exception e) { Log.e(TAG, "Bitmap rendering failed: " + e.getMessage()); }

            if (bmp != null) {
                try {
                    byte[] data = PrintPicture.POS_PrintBMP(bmp, deviceWidth, 0, 0);
                    if (data != null && sendDataByte(data)) { promise.resolve(null); return; }
                } catch (Exception e) {
                    Log.e(TAG, "Bitmap printing error: " + e.getMessage());
                } finally {
                    if (!bmp.isRecycled()) bmp.recycle();
                }
            }

            // ── FALLBACK ─────────────────────────────────────────────────────────────
            String toPrint = text;
            if (containsArabicCharacters(toPrint)) {
                try {
                    if (sendDataByte(PrinterCommand.POS_Print_Text(toPrint, "UTF-8", 0, widthTimes, heigthTimes, fonttype))) {
                        promise.resolve(null); return;
                    }
                } catch (Exception e) { Log.e(TAG, "UTF-8 fallback failed: " + e.getMessage()); }
                toPrint = toPrint.replaceAll("[^\\x00-\\x7F]", "?");
            }
            byte[] bytes = PrinterCommand.POS_Print_Text(toPrint, encoding, codepage, widthTimes, heigthTimes, fonttype);
            if (sendDataByte(bytes)) promise.resolve(null);
            else promise.reject("COMMAND_NOT_SEND");

        } catch (Exception e) {
            Log.e(TAG, "printText error: " + e.getMessage());
            promise.reject("PRINT_ERROR", e.getMessage(), e);
        }
    }

    @ReactMethod
    public void printColumn(ReadableArray columnWidths, ReadableArray columnAligns, ReadableArray columnTexts,
                            @Nullable ReadableMap options, final Promise promise) {
        if (columnWidths.size() != columnTexts.size() || columnWidths.size() != columnAligns.size()) {
            promise.reject("COLUMN_WIDTHS_ALIGNS_AND_TEXTS_NOT_MATCH"); return;
        }
        int totalLen = 0;
        for (int i = 0; i < columnWidths.size(); i++) totalLen += columnWidths.getInt(i);
        if (totalLen > deviceWidth / 8) { promise.reject("COLUNM_WIDTHS_TOO_LARGE"); return; }

        String encoding = "GBK";
        int codepage = 0, widthTimes = 0, heigthTimes = 0, fonttype = 0;
        if (options != null) {
            encoding   = options.hasKey("encoding")    ? options.getString("encoding") : "GBK";
            codepage   = options.hasKey("codepage")    ? options.getInt("codepage")    : 0;
            widthTimes = options.hasKey("widthtimes")  ? options.getInt("widthtimes")  : 0;
            heigthTimes= options.hasKey("heigthtimes") ? options.getInt("heigthtimes") : 0;
            fonttype   = options.hasKey("fonttype")    ? options.getInt("fonttype")    : 0;
        }

        List<List<String>> table = new ArrayList<>();
        int padding = 1;
        for (int i = 0; i < columnWidths.size(); i++) {
            int width = columnWidths.getInt(i) - padding;
            String text = columnTexts.getString(i);
            List<ColumnSplitedString> splited = new ArrayList<>();
            int shorter = 0, counter = 0; String temp = "";
            for (int c = 0; c < text.length(); c++) {
                char ch = text.charAt(c); int l = isChinese(ch) ? 2 : 1;
                if (l == 2) shorter++; temp += ch;
                if (counter + l < width) { counter += l; }
                else { splited.add(new ColumnSplitedString(shorter, temp)); temp = ""; counter = 0; shorter = 0; }
            }
            if (temp.length() > 0) splited.add(new ColumnSplitedString(shorter, temp));
            int align = columnAligns.getInt(i);
            List<String> formated = new ArrayList<>();
            for (ColumnSplitedString s : splited) {
                StringBuilder empty = new StringBuilder();
                for (int w = 0; w < (width + padding - s.getShorter()); w++) empty.append(" ");
                int startIdx = 0; String ss = s.getStr();
                if (align == 1 && ss.length() < (width - s.getShorter())) {
                    startIdx = (width - s.getShorter() - ss.length()) / 2;
                    if (startIdx + ss.length() > width - s.getShorter()) startIdx--;
                    if (startIdx < 0) startIdx = 0;
                } else if (align == 2 && ss.length() < (width - s.getShorter())) {
                    startIdx = width - s.getShorter() - ss.length();
                }
                empty.replace(startIdx, startIdx + ss.length(), ss);
                formated.add(empty.toString());
            }
            table.add(formated);
        }

        int maxRowCount = 0;
        for (List<String> rows : table) if (rows.size() > maxRowCount) maxRowCount = rows.size();

        StringBuilder[] rowsToPrint = new StringBuilder[maxRowCount];
        for (int column = 0; column < table.size(); column++) {
            List<String> rows = table.get(column);
            for (int row = 0; row < maxRowCount; row++) {
                if (rowsToPrint[row] == null) rowsToPrint[row] = new StringBuilder();
                if (row < rows.size()) { rowsToPrint[row].append(rows.get(row)); }
                else {
                    StringBuilder empty = new StringBuilder();
                    for (int i = 0; i < columnWidths.getInt(column); i++) empty.append(" ");
                    rowsToPrint[row].append(empty);
                }
            }
        }

        for (int i = 0; i < rowsToPrint.length; i++) {
            rowsToPrint[i].append("\n\r");
            try {
                String line = rowsToPrint[i].toString();
                sendDataByte(PrinterCommand.POS_Set_Bold(fonttype > 0 ? 1 : 0));
                if (fonttype == 0) sendDataByte(Command.ESC_ExclamationMark);

                Bitmap bmp = null;
                try { bmp = renderTextToBitmap(line, deviceWidth, false, fonttype > 0); }
                catch (Exception e) { Log.e(TAG, "Column bitmap failed: " + e.getMessage()); }

                if (bmp != null) {
                    try {
                        byte[] data = PrintPicture.POS_PrintBMP(bmp, deviceWidth, 0, 0);
                        if (data != null && sendDataByte(data)) continue;
                    } catch (Exception e) {
                        Log.e(TAG, "Column bitmap print error: " + e.getMessage());
                    } finally {
                        if (!bmp.isRecycled()) bmp.recycle();
                    }
                }
                if (containsArabicCharacters(line)) {
                    try {
                        if (sendDataByte(PrinterCommand.POS_Print_Text(line, "UTF-8", 0, widthTimes, heigthTimes, fonttype))) continue;
                    } catch (Exception e) { Log.e(TAG, "Column UTF-8 fallback failed: " + e.getMessage()); }
                    String asciiLine = line.replaceAll("[^\\x00-\\x7F]", "?");
                    if (!sendDataByte(PrinterCommand.POS_Print_Text(asciiLine, encoding, codepage, widthTimes, heigthTimes, fonttype))) {
                        promise.reject("COMMAND_NOT_SEND", "Failed row: " + i); return;
                    }
                } else {
                    if (!sendDataByte(PrinterCommand.POS_Print_Text(line, encoding, codepage, widthTimes, heigthTimes, fonttype))) {
                        promise.reject("COMMAND_NOT_SEND", "Failed row: " + i); return;
                    }
                }
            } catch (Exception e) { Log.e(TAG, "Column row " + i + " error: " + e.getMessage()); }
        }
        promise.resolve(null);
    }

    @ReactMethod
    public void setWidth(int width) { deviceWidth = width; }

    private boolean isOldAndroidVersion() {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR1;
    }

    private Bitmap.Config getOptimalBitmapConfig() {
        return isOldAndroidVersion() ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
    }

    /** Single source of truth for font size across ALL languages. */
    private int getOptimalTextSize() {
        return isOldAndroidVersion() ? 16 : 18;
    }

    private Bitmap createCenteredArabicBitmap(String text, int targetWidth, TextPaint paint) {
        try {
            int bmpWidth = Math.min(targetWidth, 384);
            int bmpHeight = isOldAndroidVersion() ? 28 : 34;
            Bitmap bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, getOptimalBitmapConfig());
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            float x = Math.max(0, (bmpWidth - paint.measureText(text)) / 2);
            canvas.drawText(text, x, isOldAndroidVersion() ? 22 : 24, paint);
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "createCenteredArabicBitmap error: " + e.getMessage());
            return null;
        }
    }

    @ReactMethod
    public void printPic(String base64encodeStr, @Nullable ReadableMap options) {
        int width = 0, leftPadding = 0;
        if (options != null) {
            width = options.hasKey("width") ? options.getInt("width") : 0;
            leftPadding = options.hasKey("left") ? options.getInt("left") : 0;
        }
        if (width > deviceWidth || width == 0) width = deviceWidth;
        byte[] bytes = Base64.decode(base64encodeStr, Base64.DEFAULT);
        Bitmap mBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (mBitmap != null) sendDataByte(PrintPicture.POS_PrintBMP(mBitmap, width, 0, leftPadding));
    }

    @ReactMethod
    public void printPicFromURL(final String picUrl, @Nullable final ReadableMap options, final Promise promise) {
        new Thread(() -> {
            HttpURLConnection connection = null; InputStream input = null;
            try {
                URL url = new URL(picUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setDoInput(true); connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
                connection.connect();
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    promise.reject("URL_ERROR", "Response: " + connection.getResponseCode()); return;
                }
                input = connection.getInputStream();
                Bitmap mBitmap = BitmapFactory.decodeStream(input);
                if (mBitmap == null) { promise.reject("DECODE_ERROR", "Failed to decode image"); return; }
                int width = 0, leftPadding = 0;
                if (options != null) {
                    width = options.hasKey("width") ? options.getInt("width") : 0;
                    leftPadding = options.hasKey("left") ? options.getInt("left") : 0;
                }
                if (width > deviceWidth || width == 0) width = deviceWidth;
                byte[] data = PrintPicture.POS_PrintBMP(mBitmap, width, 0, leftPadding);
                if (sendDataByte(data)) promise.resolve(null);
                else promise.reject("COMMAND_NOT_SEND", "Failed to send image");
                if (!mBitmap.isRecycled()) mBitmap.recycle();
            } catch (Exception e) {
                Log.e(TAG, "printPicFromURL error: " + e.getMessage());
                promise.reject("PRINT_ERROR", e.getMessage(), e);
            } finally {
                try { if (input != null) input.close(); if (connection != null) connection.disconnect(); }
                catch (Exception e) { Log.e(TAG, "Connection close error: " + e.getMessage()); }
            }
        }).start();
    }

    @ReactMethod
    public void selfTest(@Nullable Callback cb) {
        boolean result = sendDataByte(PrinterCommand.POS_Set_PrtSelfTest());
        if (cb != null) cb.invoke(result);
    }

    @ReactMethod
    public void rotate(int rotate, final Promise promise) {
        if (sendDataByte(PrinterCommand.POS_Set_Rotate(rotate))) promise.resolve(null);
        else promise.reject("COMMAND_NOT_SEND");
    }

    @ReactMethod
    public void setBlob(int weight, final Promise promise) {
        if (sendDataByte(PrinterCommand.POS_Set_Bold(weight))) promise.resolve(null);
        else promise.reject("COMMAND_NOT_SEND");
    }

    @ReactMethod
    public void printQRCode(String content, int size, int correctionLevel, final Promise promise) {
        try {
            Hashtable<EncodeHintType, Object> hints = new Hashtable<>();
            hints.put(EncodeHintType.CHARACTER_SET, "utf-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.forBits(correctionLevel));
            BitMatrix bitMatrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            int width = bitMatrix.getWidth(), height = bitMatrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++)
                    pixels[y * width + x] = bitMatrix.get(x, y) ? 0xff000000 : 0xffffffff;
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            byte[] data = PrintPicture.POS_PrintBMP(bitmap, size, 0, 0);
            if (sendDataByte(data)) promise.resolve(null);
            else promise.reject("COMMAND_NOT_SEND");
        } catch (Exception e) { promise.reject(e.getMessage(), e); }
    }

    @ReactMethod
    public void printBarCode(String str, int nType, int nWidthX, int nHeight, int nHriFontType, int nHriFontPosition) {
        sendDataByte(PrinterCommand.getBarCodeCommand(str, nType, nWidthX, nHeight, nHriFontType, nHriFontPosition));
    }

    @ReactMethod
    public void openDrawer(int nMode, int nTime1, int nTime2) {
        try { sendDataByte(PrinterCommand.POS_Set_Cashbox(nMode, nTime1, nTime2)); }
        catch (Exception e) { Log.d(TAG, e.getMessage()); }
    }

    @ReactMethod
    public void cutOnePoint() {
        try { sendDataByte(PrinterCommand.POS_Set_Cut(1)); }
        catch (Exception e) { Log.d(TAG, e.getMessage()); }
    }

    private boolean sendDataByte(byte[] data) {
        if (data == null || mService.getState() != BluetoothService.STATE_CONNECTED) return false;
        mService.write(data); return true;
    }

    private static boolean isChinese(char c) {
        Character.UnicodeBlock ub = Character.UnicodeBlock.of(c);
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || ub == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || ub == Character.UnicodeBlock.GENERAL_PUNCTUATION;
    }

    private static boolean containsArabicCharacters(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(value.charAt(i));
            if (block == Character.UnicodeBlock.ARABIC
                    || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A
                    || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B
                    || block == Character.UnicodeBlock.ARABIC_SUPPLEMENT) return true;
        }
        return false;
    }

    private Bitmap renderTextToBitmap(String text, int targetWidth, boolean bold) {
        return renderTextToBitmap(text, targetWidth, false, bold);
    }

    /**
     * Render any text into a printer-width bitmap.
     *
     * CENTERING FIX:
     *   StaticLayout with ALIGN_CENTER already centres every line within its layout
     *   width (== bmpWidth for centered rows). Previously we also translated the
     *   canvas by (bmpWidth - textWidth) / 2, which double-shifted English text to
     *   the right of centre. That extra translation has been removed.
     *
     *   Rule: canvas is only ever translated for non-centered content rows
     *   (by CONTENT_SIDE_MARGIN to inset key-value lines from the paper edge).
     */
    private Bitmap renderTextToBitmap(String text, int targetWidth, boolean center, boolean bold) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            final int sideMargin = center ? 0 : CONTENT_SIDE_MARGIN;
            final int contentWidth = targetWidth - sideMargin * 2;

            TextPaint paint = new TextPaint();
            paint.setAntiAlias(false);
            paint.setColor(Color.BLACK);
            paint.setTextSize(getOptimalTextSize());
            paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL));
            paint.setFakeBoldText(bold);
            paint.setStrokeWidth(0);
            paint.setLinearText(true);
            paint.setSubpixelText(false);

            StaticLayout layout = null;
            try {
                layout = new StaticLayout(text, paint, contentWidth,
                        center ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL,
                        1.0f, 0f, false);
            } catch (Exception e) {
                Log.e(TAG, "StaticLayout failed: " + e.getMessage());
                if (center) return createCenteredArabicBitmap(text, targetWidth, paint);
                return createSimpleTextBitmap(text, targetWidth, paint, false);
            }

            if (layout == null) {
                if (center) return createCenteredArabicBitmap(text, targetWidth, paint);
                return createSimpleTextBitmap(text, targetWidth, paint, false);
            }

            int bmpWidth = Math.min(targetWidth, 384);
            int bmpHeight = Math.max(layout.getHeight(), 20);

            Runtime rt = Runtime.getRuntime();
            long available = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
            if ((long) bmpWidth * bmpHeight * 2 > available * 0.5) {
                if (center) return createCenteredArabicBitmap(text, targetWidth, paint);
                return createSimpleTextBitmap(text, targetWidth, paint, false);
            }

            Bitmap bitmap = null;
            try {
                bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, getOptimalBitmapConfig());
                if (bitmap == null) return createSimpleTextBitmap(text, targetWidth, paint, center);

                Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(Color.WHITE);
                canvas.save();

                if (!center) {
                    // Non-centred rows: indent from paper edge only
                    canvas.translate(sideMargin, 0);
                }
                // Centred rows: NO translation — StaticLayout ALIGN_CENTER handles it.
                // Adding a translation here was causing English header text to appear
                // shifted right of centre (double-centering bug).

                layout.draw(canvas);
                canvas.restore();
                return bitmap;
            } catch (OutOfMemoryError e) {
                Log.e(TAG, "OOM: " + e.getMessage());
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                if (center) return createCenteredArabicBitmap(text, targetWidth, paint);
                return createSimpleTextBitmap(text, targetWidth, paint, false);
            }
        } catch (Exception e) {
            Log.e(TAG, "renderTextToBitmap error: " + e.getMessage());
            return null;
        }
    }

    private Bitmap createSimpleTextBitmap(String text, int targetWidth, TextPaint paint, boolean center) {
        try {
            int bmpWidth = Math.min(targetWidth, 384);
            int bmpHeight = isOldAndroidVersion() ? 28 : 34;
            Bitmap bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, getOptimalBitmapConfig());
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            float x = center ? Math.max(0, (bmpWidth - paint.measureText(text)) / 2) : CONTENT_SIDE_MARGIN;
            canvas.drawText(text, x, isOldAndroidVersion() ? 22 : 24, paint);
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "createSimpleTextBitmap error: " + e.getMessage());
            return null;
        }
    }

    /**
     * printTextCentered — unified bitmap path for all text.
     */
    @ReactMethod
    public void printTextCentered(String text, @Nullable ReadableMap options, final Promise promise) {
        try {
            if (text == null || text.trim().isEmpty()) { promise.resolve(null); return; }

            String encoding = "GBK";
            int codepage = 0, widthTimes = 0, heigthTimes = 0, fonttype = 0;
            if (options != null) {
                encoding   = options.hasKey("encoding")    ? options.getString("encoding") : "GBK";
                codepage   = options.hasKey("codepage")    ? options.getInt("codepage")    : 0;
                widthTimes = options.hasKey("widthtimes")  ? options.getInt("widthtimes")  : 0;
                heigthTimes= options.hasKey("heigthtimes") ? options.getInt("heigthtimes") : 0;
                fonttype   = options.hasKey("fonttype")    ? options.getInt("fonttype")    : 0;
            }

            sendDataByte(PrinterCommand.POS_Set_Bold(fonttype > 0 ? 1 : 0));
            if (fonttype == 0) sendDataByte(Command.ESC_ExclamationMark);
            if (text.startsWith("##BITMAP##")) text = text.replace("##BITMAP##", "");

            // ── UNIFIED BITMAP PATH ──────────────────────────────────────────────────
            Bitmap bmp = null;
            try { bmp = renderTextToBitmap(text, deviceWidth, true, fonttype > 0); }
            catch (Exception e) { Log.e(TAG, "Centered bitmap failed: " + e.getMessage()); }

            if (bmp != null) {
                try {
                    byte[] data = PrintPicture.POS_PrintBMP(bmp, deviceWidth, 0, 0);
                    if (data != null && sendDataByte(data)) { promise.resolve(null); return; }
                } catch (Exception e) {
                    Log.e(TAG, "Centered bitmap print error: " + e.getMessage());
                } finally {
                    if (!bmp.isRecycled()) bmp.recycle();
                }
            }

            // ── FALLBACK ─────────────────────────────────────────────────────────────
            try {
                if (!sendDataByte(PrinterCommand.POS_S_Align(1))) {
                    promise.reject("COMMAND_NOT_SEND", "Failed to set alignment"); return;
                }
                String toPrint = text;
                if (containsArabicCharacters(text)) {
                    try {
                        if (sendDataByte(PrinterCommand.POS_Print_Text(toPrint, "UTF-8", 0, widthTimes, heigthTimes, fonttype))) {
                            sendDataByte(PrinterCommand.POS_S_Align(0));
                            promise.resolve(null); return;
                        }
                    } catch (Exception e) { Log.e(TAG, "UTF-8 fallback: " + e.getMessage()); }
                    toPrint = toPrint.replaceAll("[^\\x00-\\x7F]", "?");
                }
                if (fonttype == 0) sendDataByte(Command.ESC_ExclamationMark);
                byte[] bytes = PrinterCommand.POS_Print_Text(toPrint, encoding, codepage, widthTimes, heigthTimes, fonttype);
                if (sendDataByte(bytes)) {
                    sendDataByte(PrinterCommand.POS_S_Align(0)); promise.resolve(null);
                } else {
                    promise.reject("COMMAND_NOT_SEND", "Failed to print");
                }
            } catch (Exception e) {
                promise.reject("PRINT_ERROR", e.getMessage(), e);
            }
        } catch (Exception e) {
            Log.e(TAG, "printTextCentered error: " + e.getMessage());
            promise.reject("PRINT_ERROR", e.getMessage(), e);
        }
    }

    @Override
    public void onBluetoothServiceStateChanged(int state, Map<String, Object> boundle) {}

    private static class ColumnSplitedString {
        private final int shorter; private final String str;
        ColumnSplitedString(int shorter, String str) { this.shorter = shorter; this.str = str; }
        int getShorter() { return shorter; }
        String getStr() { return str; }
    }
}