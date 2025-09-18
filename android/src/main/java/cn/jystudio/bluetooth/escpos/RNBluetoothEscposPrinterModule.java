
package cn.jystudio.bluetooth.escpos;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import java.nio.charset.Charset;
import java.util.*;

public class RNBluetoothEscposPrinterModule extends ReactContextBaseJavaModule
        implements BluetoothServiceStateObserver {
    private static final String TAG = "BluetoothEscposPrinter";

    public static final int WIDTH_58 = 384;
    public static final int WIDTH_80 = 576;
    private final ReactApplicationContext reactContext;
    /******************************************************************************************************/

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
    public
    @Nullable
    Map<String, Object> getConstants() {
        Map<String, Object> constants = new HashMap<>();
        constants.put("width58", WIDTH_58);
        constants.put("width80", WIDTH_80);
        return constants;
    }

    @ReactMethod
    public void printerInit(final Promise promise){
        if(sendDataByte(PrinterCommand.POS_Set_PrtInit())){
            promise.resolve(null);
        }else{
            promise.reject("COMMAND_NOT_SEND");
        }
    }

    @ReactMethod
    public void printAndFeed(int feed,final Promise promise){
        if(sendDataByte(PrinterCommand.POS_Set_PrtAndFeedPaper(feed))){
            promise.resolve(null);
        }else{
            promise.reject("COMMAND_NOT_SEND");
        }
    }

    @ReactMethod
    public void printerLeftSpace(int sp,final Promise promise){
        if(sendDataByte(PrinterCommand.POS_Set_LeftSP(sp))){
            promise.resolve(null);
        }else{
            promise.reject("COMMAND_NOT_SEND");
        }
    }

    @ReactMethod
    public void printerLineSpace(int sp,final Promise promise){
        byte[] command = PrinterCommand.POS_Set_DefLineSpace();
        if(sp>0){
            command = PrinterCommand.POS_Set_LineSpace(sp);
        }
        if(command==null || !sendDataByte(command)){
            promise.reject("COMMAND_NOT_SEND");
        }else{
            promise.resolve(null);
        }
    }

    /**
     * Under line switch, 0-off,1-on,2-deeper
     * @param line 0-off,1-on,2-deeper
     */
    @ReactMethod
    public void printerUnderLine(int line,final Promise promise){
        if(sendDataByte(PrinterCommand.POS_Set_UnderLine(line))){
            promise.resolve(null);
        }else{
            promise.reject("COMMAND_NOT_SEND");
        }
    }

    /**
     * When n=0 or 48, left justification is enabled
     * When n=1 or 49, center justification is enabled
     * When n=2 or 50, right justification is enabled
     * @param align
     * @param promise
     */
    @ReactMethod
    public void printerAlign(int align,final Promise promise){
        Log.d(TAG,"Align:"+align);
        if(sendDataByte(PrinterCommand.POS_S_Align(align))){
            promise.resolve(null);
        }else{
            promise.reject("COMMAND_NOT_SEND");
        }
    }


    @ReactMethod
    public void printText(String text, @Nullable  ReadableMap options, final Promise promise) {
        try {
            if (text == null || text.trim().isEmpty()) {
                promise.resolve(null);
                return;
            }

            String encoding = "GBK";
            int codepage = 0;
            int widthTimes = 0;
            int heigthTimes=0;
            int fonttype=0;
            if(options!=null) {
                encoding = options.hasKey("encoding") ? options.getString("encoding") : "GBK";
                codepage = options.hasKey("codepage") ? options.getInt("codepage") : 0;
                widthTimes = options.hasKey("widthtimes") ? options.getInt("widthtimes") : 0;
                heigthTimes = options.hasKey("heigthtimes") ? options.getInt("heigthtimes") : 0;
                fonttype = options.hasKey("fonttype") ? options.getInt("fonttype") : 0;
            }
            
            String toPrint = text;
            
            if (containsArabicCharacters(toPrint)) {
                Log.d(TAG, "Printing Arabic text: " + toPrint);
                
                // Try bitmap rendering first
                Bitmap bmp = null;
                try {
                    bmp = renderTextToBitmap(toPrint, deviceWidth);
                } catch (Exception e) {
                    Log.e(TAG, "Bitmap rendering failed: " + e.getMessage());
                }
                
                if (bmp != null) {
                    try {
                        byte[] data = PrintPicture.POS_PrintBMP(bmp, deviceWidth, 0, 0);
                        if (data != null && sendDataByte(data)) {
                            promise.resolve(null);
                            return;
                        } else {
                            Log.w(TAG, "Bitmap printing failed, trying fallback");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Bitmap printing error: " + e.getMessage());
                    } finally {
                        // Clean up bitmap to prevent memory leaks
                        if (bmp != null && !bmp.isRecycled()) {
                            bmp.recycle();
                        }
                    }
                }
                
                // Fallback: Try to print as regular text with UTF-8 encoding
                Log.d(TAG, "Using fallback text printing for Arabic");
                try {
                    byte[] bytes = PrinterCommand.POS_Print_Text(toPrint, "UTF-8", 0, widthTimes, heigthTimes, fonttype);
                    if (sendDataByte(bytes)) {
                        promise.resolve(null);
                        return;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "UTF-8 fallback failed: " + e.getMessage());
                }
                
                // Last resort: Print as ASCII with question marks
                String asciiText = toPrint.replaceAll("[^\\x00-\\x7F]", "?");
                byte[] bytes = PrinterCommand.POS_Print_Text(asciiText, encoding, codepage, widthTimes, heigthTimes, fonttype);
                if (sendDataByte(bytes)) {
                    promise.resolve(null);
                } else {
                    promise.reject("AR_RENDER_FAILED", "All Arabic text rendering methods failed");
                }
            } else {
                byte[] bytes = PrinterCommand.POS_Print_Text(toPrint, encoding, codepage, widthTimes, heigthTimes, fonttype);
                if (sendDataByte(bytes)) {
                    promise.resolve(null);
                } else {
                    promise.reject("COMMAND_NOT_SEND");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "printText error: " + e.getMessage());
            promise.reject("PRINT_ERROR", e.getMessage(), e);
        }
    }

    @ReactMethod
    public void printColumn(ReadableArray columnWidths,ReadableArray columnAligns,ReadableArray columnTexts,
                            @Nullable ReadableMap options,final Promise promise){
        if(columnWidths.size()!=columnTexts.size() || columnWidths.size()!=columnAligns.size()){
            promise.reject("COLUMN_WIDTHS_ALIGNS_AND_TEXTS_NOT_MATCH");
            return;
        }
            int totalLen = 0;
            for(int i=0;i<columnWidths.size();i++){
                totalLen+=columnWidths.getInt(i);
            }
            int maxLen = deviceWidth/8;
            if(totalLen>maxLen){
                promise.reject("COLUNM_WIDTHS_TOO_LARGE");
                return;
            }

        String encoding = "GBK";
        int codepage = 0;
        int widthTimes = 0;
        int heigthTimes = 0;
        int fonttype = 0;
        if (options != null) {
            encoding = options.hasKey("encoding") ? options.getString("encoding") : "GBK";
            codepage = options.hasKey("codepage") ? options.getInt("codepage") : 0;
            widthTimes = options.hasKey("widthtimes") ? options.getInt("widthtimes") : 0;
            heigthTimes = options.hasKey("heigthtimes") ? options.getInt("heigthtimes") : 0;
            fonttype = options.hasKey("fonttype") ? options.getInt("fonttype") : 0;
        }
        Log.d(TAG,"encoding: "+encoding);

        /**
         * [column1-1,
         * column1-2,
         * column1-3 ... column1-n]
         * ,
         *  [column2-1,
         * column2-2,
         * column2-3 ... column2-n]
         *
         * ...
         *
         */
        List<List<String>> table = new ArrayList<List<String>>();

        /**splits the column text to few rows and applies the alignment **/
        int padding = 1;
        for(int i=0;i<columnWidths.size();i++){
            int width =columnWidths.getInt(i)-padding;//1 char padding
            String text = String.copyValueOf(columnTexts.getString(i).toCharArray());
            List<ColumnSplitedString> splited = new ArrayList<ColumnSplitedString>();
            int shorter = 0;
            int counter = 0;
            String temp = "";
            for(int c=0;c<text.length();c++){
                char ch = text.charAt(c);
                int l = isChinese(ch)?2:1;
                if (l==2){
                    shorter++;
                }
                temp=temp+ch;

                if(counter+l<width){
                   counter = counter+l;
                }else{
                    splited.add(new ColumnSplitedString(shorter,temp));
                    temp = "";
                    counter=0;
                    shorter=0;
                }
            }
            if(temp.length()>0) {
                splited.add(new ColumnSplitedString(shorter,temp));
            }
            int align = columnAligns.getInt(i);

            List<String> formated = new ArrayList<String>();
            for(ColumnSplitedString s: splited){
                StringBuilder empty = new StringBuilder();
                for(int w=0;w<(width+padding-s.getShorter());w++){
                    empty.append(" ");
                }
                int startIdx = 0;
                String ss = s.getStr();
                if(align == 1 && ss.length()<(width-s.getShorter())){
                    startIdx = (width-s.getShorter()-ss.length())/2;
                    if(startIdx+ss.length()>width-s.getShorter()){
                        startIdx--;
                    }
                    if(startIdx<0){
                        startIdx=0;
                    }
                }else if(align==2 && ss.length()<(width-s.getShorter())){
                    startIdx =width - s.getShorter()-ss.length();
                }
                Log.d(TAG,"empty.replace("+startIdx+","+(startIdx+ss.length())+","+ss+")");
                empty.replace(startIdx,startIdx+ss.length(),ss);
                formated.add(empty.toString());
            }
            table.add(formated);

        }

        /**  try to find the max row count of the table **/
        int maxRowCount = 0;
        for(int i=0;i<table.size()/*column count*/;i++){
            List<String> rows = table.get(i); // row data in current column
            if(rows.size()>maxRowCount){maxRowCount = rows.size();}// try to find the max row count;
        }

        /** loop table again to fill the rows **/
        StringBuilder[] rowsToPrint = new StringBuilder[maxRowCount];
        for(int column=0;column<table.size()/*column count*/;column++){
            List<String> rows = table.get(column); // row data in current column
            for(int row=0;row<maxRowCount;row++){
                if(rowsToPrint[row]==null){
                    rowsToPrint[row] = new StringBuilder();
                }
                if(row<rows.size()){
                    //got the row of this column
                    rowsToPrint[row].append(rows.get(row));
                }else{
                    int w =columnWidths.getInt(column);
                    StringBuilder empty = new StringBuilder();
                   for(int i=0;i<w;i++){
                       empty.append(" ");
                   }
                    rowsToPrint[row].append(empty.toString());//Append spaces to ensure the format
                }
            }
        }

        /** loops the rows and print **/
        for(int i=0;i<rowsToPrint.length;i++){
            rowsToPrint[i].append("\n\r");//wrap line..
            try {
                String line = rowsToPrint[i].toString();
                if (containsArabicCharacters(line)) {
                    Log.d(TAG, "Printing Arabic column text: " + line);
                    
                    // Try bitmap rendering first
                    Bitmap bmp = null;
                    try {
                        bmp = renderTextToBitmap(line, deviceWidth);
                    } catch (Exception e) {
                        Log.e(TAG, "Column bitmap rendering failed: " + e.getMessage());
                    }
                    
                    if (bmp != null) {
                        try {
                            byte[] data = PrintPicture.POS_PrintBMP(bmp, deviceWidth, 0, 0);
                            if (data != null && sendDataByte(data)) {
                                // Success, continue to next row
                                continue;
                            } else {
                                Log.w(TAG, "Column bitmap printing failed, trying fallback");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Column bitmap printing error: " + e.getMessage());
                        } finally {
                            // Clean up bitmap to prevent memory leaks
                            if (bmp != null && !bmp.isRecycled()) {
                                bmp.recycle();
                            }
                        }
                    }
                    
                    // Fallback: Try UTF-8 encoding
                    try {
                        if (sendDataByte(PrinterCommand.POS_Print_Text(line, "UTF-8", 0, widthTimes, heigthTimes, fonttype))) {
                            continue; // Success, continue to next row
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Column UTF-8 fallback failed: " + e.getMessage());
                    }
                    
                    // Last resort: Print as ASCII with question marks
                    String asciiLine = line.replaceAll("[^\\x00-\\x7F]", "?");
                    if (!sendDataByte(PrinterCommand.POS_Print_Text(asciiLine, encoding, codepage, widthTimes, heigthTimes, fonttype))) {
                        Log.e(TAG, "Column ASCII fallback also failed");
                        promise.reject("COMMAND_NOT_SEND", "Failed to print column row: " + i);
                        return;
                    }
                } else {
                    if (!sendDataByte(PrinterCommand.POS_Print_Text(line, encoding, codepage, widthTimes, heigthTimes, fonttype))) {
                        promise.reject("COMMAND_NOT_SEND", "Failed to print column row: " + i);
                        return;
                    }
                }
            }catch (Exception e){
                Log.e(TAG, "Error printing column row " + i + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        promise.resolve(null);
    }

    @ReactMethod
    public void setWidth(int width) {
        deviceWidth = width;
    }

    /**
     * Check if the device is running Android 4.2.2 or older
     */
    private boolean isOldAndroidVersion() {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR1; // API 17 = Android 4.2.2
    }

    /**
     * Get device-specific bitmap configuration for better compatibility
     */
    private Bitmap.Config getOptimalBitmapConfig() {
        if (isOldAndroidVersion()) {
            return Bitmap.Config.RGB_565; // More memory efficient for older devices
        } else {
            return Bitmap.Config.ARGB_8888; // Better quality for newer devices
        }
    }

    /**
     * Get device-specific text size for better compatibility
     */
    private int getOptimalTextSize() {
        if (isOldAndroidVersion()) {
            return 18; // Smaller text for older devices
        } else {
            return 24; // Standard text size
        }
    }

    @ReactMethod
    public void printPic(String base64encodeStr, @Nullable  ReadableMap options) {
        int width = 0;
        int leftPadding = 0;
        if(options!=null){
            width = options.hasKey("width") ? options.getInt("width") : 0;
            leftPadding = options.hasKey("left")?options.getInt("left") : 0;
        }

        //cannot larger then devicesWith;
        if(width > deviceWidth || width == 0){
            width = deviceWidth;
        }

        byte[] bytes = Base64.decode(base64encodeStr, Base64.DEFAULT);
        Bitmap mBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        int nMode = 0;
        if (mBitmap != null) {
            /**
             * Parameters:
             * mBitmap  要打印的图片
             * nWidth   打印宽度（58和80）
             * nMode    打印模式
             * Returns: byte[]
             */
            byte[] data = PrintPicture.POS_PrintBMP(mBitmap, width, nMode, leftPadding);
            //  SendDataByte(buffer);
            sendDataByte(Command.ESC_Init);
            sendDataByte(Command.LF);
            sendDataByte(data);
            sendDataByte(PrinterCommand.POS_Set_PrtAndFeedPaper(30));
            sendDataByte(PrinterCommand.POS_Set_Cut(1));
            sendDataByte(PrinterCommand.POS_Set_PrtInit());
        }
    }


    @ReactMethod
    public void selfTest(@Nullable Callback cb) {
        boolean result = sendDataByte(PrinterCommand.POS_Set_PrtSelfTest());
        if (cb != null) {
            cb.invoke(result);
        }
    }

    /**
     * Rotate 90 degree, 0-no rotate, 1-rotate
     * @param rotate  0-no rotate, 1-rotate
     */
    @ReactMethod
    public void rotate(int rotate,final Promise promise) {
        if(sendDataByte(PrinterCommand.POS_Set_Rotate(rotate))){
            promise.resolve(null);
        }else{
            promise.reject("COMMAND_NOT_SEND");
        }
    }

    @ReactMethod
    public void setBlob(int weight,final Promise promise) {
        if(sendDataByte(PrinterCommand.POS_Set_Bold(weight))){
            promise.resolve(null);
        }else{
            promise.reject("COMMAND_NOT_SEND");
        }
    }

    @ReactMethod
    public void printQRCode(String content, int size, int correctionLevel, final Promise promise) {
        try {
            Log.i(TAG, "生成的文本：" + content);
            // 把输入的文本转为二维码
            Hashtable<EncodeHintType, Object> hints = new Hashtable<EncodeHintType, Object>();
            hints.put(EncodeHintType.CHARACTER_SET, "utf-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.forBits(correctionLevel));
            BitMatrix bitMatrix = new QRCodeWriter().encode(content,
                    BarcodeFormat.QR_CODE, size, size, hints);

            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();

            System.out.println("w:" + width + "h:"
                    + height);

            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (bitMatrix.get(x, y)) {
                        pixels[y * width + x] = 0xff000000;
                    } else {
                        pixels[y * width + x] = 0xffffffff;
                    }
                }
            }

            Bitmap bitmap = Bitmap.createBitmap(width, height,
                    Bitmap.Config.ARGB_8888);

            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);

            //TODO: may need a left padding to align center.
            byte[] data = PrintPicture.POS_PrintBMP(bitmap, size, 0, 0);
            if (sendDataByte(data)) {
                promise.resolve(null);
            } else {
                promise.reject("COMMAND_NOT_SEND");
            }
        } catch (Exception e) {
            promise.reject(e.getMessage(), e);
        }
    }

    @ReactMethod
    public void printBarCode(String str, int nType, int nWidthX, int nHeight,
                             int nHriFontType, int nHriFontPosition) {
        byte[] command = PrinterCommand.getBarCodeCommand(str, nType, nWidthX, nHeight, nHriFontType, nHriFontPosition);
        sendDataByte(command);
    }

    @ReactMethod
    public void openDrawer(int nMode, int nTime1, int nTime2) {
        try{
            byte[] command = PrinterCommand.POS_Set_Cashbox(nMode, nTime1, nTime2);
            sendDataByte(command);

         }catch (Exception e){
            Log.d(TAG, e.getMessage());
        }
    }


    @ReactMethod
    public void cutOnePoint() {
        try{
            byte[] command = PrinterCommand.POS_Set_Cut(1);
            sendDataByte(command);

         }catch (Exception e){
            Log.d(TAG, e.getMessage());
        }
    }    

    private boolean sendDataByte(byte[] data) {
        if (data==null || mService.getState() != BluetoothService.STATE_CONNECTED) {
            return false;
        }
        mService.write(data);
        return true;
    }

    // 根据Unicode编码完美的判断中文汉字和符号
    private static boolean isChinese(char c) {
        Character.UnicodeBlock ub = Character.UnicodeBlock.of(c);
        if (ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || ub == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || ub == Character.UnicodeBlock.GENERAL_PUNCTUATION) {
            return true;
        }
        return false;
    }

    /**
     * Detect if a string contains Arabic characters.
     */
    private static boolean containsArabicCharacters(String value) {
        if (value == null || value.length() == 0) return false;
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(value.charAt(i));
            if (block == Character.UnicodeBlock.ARABIC
                    || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A
                    || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B
                    || block == Character.UnicodeBlock.ARABIC_SUPPLEMENT) {
                return true;
            }
        }
        return false;
    }

    /**
     * Render text (including Arabic shaping/RTL) into a bitmap sized to printer width.
     * Enhanced for Android 4.2.2 compatibility with better memory management and error handling.
     */
    private Bitmap renderTextToBitmap(String text, int targetWidth) {
        return renderTextToBitmap(text, targetWidth, false);
    }

    private Bitmap renderTextToBitmap(String text, int targetWidth, boolean center) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        try {
            // Use device-specific bitmap configuration
            Bitmap.Config config = getOptimalBitmapConfig();
            
            TextPaint paint = new TextPaint();
            paint.setAntiAlias(!isOldAndroidVersion()); // Disable anti-aliasing for older devices
            paint.setColor(Color.BLACK);
            paint.setTextSize(getOptimalTextSize()); // Device-specific text size
            
            // Simple RTL handling for Android 4.2.2 compatibility
            CharSequence sequence = text;
            if (containsArabicCharacters(text)) {
                // Add RLM (Right-to-Left Mark) for proper Arabic rendering
                sequence = "\u200F" + text + "\u200F";
            }

            StaticLayout layout = null;
            try {
                // Use simpler StaticLayout constructor for Android 4.2.2 compatibility
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    layout = new StaticLayout(sequence, paint, targetWidth, 
                        center ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL, 
                        1.0f, 0f, false);
                } else {
                    // Fallback for older Android versions
                    layout = new StaticLayout(sequence, paint, targetWidth, 
                        center ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL, 
                        1.0f, 0f, false);
                }
            } catch (Exception e) {
                Log.e(TAG, "StaticLayout creation failed: " + e.getMessage());
                return createSimpleTextBitmap(text, targetWidth, paint, center);
            }

            if (layout == null) {
                return createSimpleTextBitmap(text, targetWidth, paint, center);
            }

            int bmpWidth = Math.min(targetWidth, 384); // Limit width for memory efficiency
            int bmpHeight = Math.max(layout.getHeight(), 20); // Ensure minimum height
            
            // Check available memory before creating bitmap
            Runtime runtime = Runtime.getRuntime();
            long availableMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
            long requiredMemory = (long) bmpWidth * bmpHeight * 2; // RGB_565 = 2 bytes per pixel
            
            if (requiredMemory > availableMemory * 0.5) { // Use max 50% of available memory
                Log.w(TAG, "Insufficient memory for bitmap creation, using fallback");
                return createSimpleTextBitmap(text, targetWidth, paint, center);
            }

            Bitmap bitmap = null;
            try {
                bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, config);
                if (bitmap == null) {
                    return createSimpleTextBitmap(text, targetWidth, paint, center);
                }
                
                Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(Color.WHITE);
                
                // Draw text with proper positioning
                canvas.save();
                if (center) {
                    float textWidth = paint.measureText(sequence.toString());
                    float x = (bmpWidth - textWidth) / 2;
                    canvas.translate(Math.max(0, x), 0);
                }
                layout.draw(canvas);
                canvas.restore();
                
                return bitmap;
            } catch (OutOfMemoryError e) {
                Log.e(TAG, "OutOfMemoryError creating bitmap: " + e.getMessage());
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                return createSimpleTextBitmap(text, targetWidth, paint, center);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in renderTextToBitmap: " + e.getMessage());
            return null;
        }
    }

    /**
     * Fallback method to create a simple text bitmap when StaticLayout fails
     */
    private Bitmap createSimpleTextBitmap(String text, int targetWidth, TextPaint paint, boolean center) {
        try {
            int bmpWidth = Math.min(targetWidth, 384);
            int bmpHeight = isOldAndroidVersion() ? 25 : 30; // Smaller height for older devices
            
            Bitmap bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, getOptimalBitmapConfig());
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            
            float x = 0;
            if (center) {
                float textWidth = paint.measureText(text);
                x = (bmpWidth - textWidth) / 2;
            }
            
            float y = isOldAndroidVersion() ? 18 : 20; // Adjust Y position for older devices
            canvas.drawText(text, Math.max(0, x), y, paint);
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error in createSimpleTextBitmap: " + e.getMessage());
            return null;
        }
    }

    /**
     * Print centered text (bitmap for Arabic; ESC/POS alignment for others)
     * Enhanced for Android 4.2.2 compatibility
     */
    @ReactMethod
    public void printTextCentered(String text, @Nullable ReadableMap options, final Promise promise) {
        try {
            if (text == null || text.trim().isEmpty()) {
                promise.resolve(null);
                return;
            }

            String encoding = "GBK";
            int codepage = 0;
            int widthTimes = 0;
            int heigthTimes=0;
            int fonttype=0;
            if(options!=null) {
                encoding = options.hasKey("encoding") ? options.getString("encoding") : "GBK";
                codepage = options.hasKey("codepage") ? options.getInt("codepage") : 0;
                widthTimes = options.hasKey("widthtimes") ? options.getInt("widthtimes") : 0;
                heigthTimes = options.hasKey("heigthtimes") ? options.getInt("heigthtimes") : 0;
                fonttype = options.hasKey("fonttype") ? options.getInt("fonttype") : 0;
            }

            if (containsArabicCharacters(text)) {
                Log.d(TAG, "Printing centered Arabic text: " + text);
                
                // Try bitmap rendering first
                Bitmap bmp = null;
                try {
                    bmp = renderTextToBitmap(text, deviceWidth, true);
                } catch (Exception e) {
                    Log.e(TAG, "Centered bitmap rendering failed: " + e.getMessage());
                }
                
                if (bmp != null) {
                    try {
                        byte[] data = PrintPicture.POS_PrintBMP(bmp, deviceWidth, 0, 0);
                        if (data != null && sendDataByte(data)) {
                            promise.resolve(null);
                            return;
                        } else {
                            Log.w(TAG, "Centered bitmap printing failed, trying fallback");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Centered bitmap printing error: " + e.getMessage());
                    } finally {
                        // Clean up bitmap to prevent memory leaks
                        if (bmp != null && !bmp.isRecycled()) {
                            bmp.recycle();
                        }
                    }
                }
                
                // Fallback: Use ESC/POS center alignment with UTF-8
                Log.d(TAG, "Using fallback centered text printing for Arabic");
                try {
                    if (sendDataByte(PrinterCommand.POS_S_Align(1))) {
                        byte[] bytes = PrinterCommand.POS_Print_Text(text, "UTF-8", 0, widthTimes, heigthTimes, fonttype);
                        if (sendDataByte(bytes)) {
                            sendDataByte(PrinterCommand.POS_S_Align(0)); // Reset alignment
                            promise.resolve(null);
                            return;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Centered UTF-8 fallback failed: " + e.getMessage());
                }
                
                // Last resort: Print as ASCII with question marks, centered
                String asciiText = text.replaceAll("[^\\x00-\\x7F]", "?");
                if (sendDataByte(PrinterCommand.POS_S_Align(1))) {
                    byte[] bytes = PrinterCommand.POS_Print_Text(asciiText, encoding, codepage, widthTimes, heigthTimes, fonttype);
                    if (sendDataByte(bytes)) {
                        sendDataByte(PrinterCommand.POS_S_Align(0)); // Reset alignment
                        promise.resolve(null);
                    } else {
                        promise.reject("AR_RENDER_FAILED", "All centered Arabic text rendering methods failed");
                    }
                } else {
                    promise.reject("COMMAND_NOT_SEND", "Failed to set center alignment");
                }
            } else {
                // ESC/POS center align -> print -> left align
                try {
                    if (!sendDataByte(PrinterCommand.POS_S_Align(1))) { 
                        promise.reject("COMMAND_NOT_SEND", "Failed to set center alignment"); 
                        return; 
                    }
                    byte[] bytes = PrinterCommand.POS_Print_Text(text, encoding, codepage, widthTimes, heigthTimes, fonttype);
                    if (!sendDataByte(bytes)) { 
                        promise.reject("COMMAND_NOT_SEND", "Failed to print text"); 
                        return; 
                    }
                    if (!sendDataByte(PrinterCommand.POS_S_Align(0))) { 
                        Log.w(TAG, "Failed to reset alignment, but text was printed");
                    }
                    promise.resolve(null);
                } catch (Exception e) {
                    Log.e(TAG, "Centered text printing error: " + e.getMessage());
                    promise.reject("PRINT_ERROR", e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "printTextCentered error: " + e.getMessage());
            promise.reject("PRINT_ERROR", e.getMessage(), e);
        }
    }

    @Override
    public void onBluetoothServiceStateChanged(int state, Map<String, Object> boundle) {

    }

    /****************************************************************************************************/

    private static class ColumnSplitedString{
        private int shorter;
        private String str;

        public ColumnSplitedString(int shorter, String str) {
            this.shorter = shorter;
            this.str = str;
        }

        public int getShorter() {
            return shorter;
        }

        public String getStr() {
            return str;
        }
    }

}
