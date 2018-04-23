package cn.inu1255.cordova.proxy.core;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Created by cn.inu1255.cordova.proxy1255 on 2018/4/15.
 */

public class Utils {
    public static String saveBitmap(Drawable drawable, String name) {
        // 创建一个位于SD卡上的文件
        File dir = new File(Environment.getExternalStorageDirectory() + "/app_icons");
        if (!dir.exists()) dir.mkdir();
        File file = new File(dir, name + ".jpg");
        if (file.exists()) return file.toURI().toString();
        FileOutputStream out = null;
        try {
            // 打开指定文件输出流
            out = new FileOutputStream(file);
            // 将位图输出到指定文件
            ((BitmapDrawable) drawable).getBitmap().compress(Bitmap.CompressFormat.JPEG, 100, out);
            out.close();
            return file.toURI().toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
