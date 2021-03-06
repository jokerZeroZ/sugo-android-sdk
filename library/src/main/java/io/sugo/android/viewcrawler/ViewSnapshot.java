package io.sugo.android.viewcrawler;


import android.annotation.TargetApi;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.widget.NestedScrollView;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Base64OutputStream;
import android.util.DisplayMetrics;
import android.util.JsonWriter;
import android.util.Log;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.WebView;
import android.widget.HorizontalScrollView;
import android.widget.RelativeLayout;
import android.widget.ScrollView;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPOutputStream;

import io.sugo.android.metrics.ResourceIds;
import io.sugo.android.metrics.SGConfig;
import io.sugo.android.metrics.SugoAPI;
import io.sugo.android.metrics.SugoWebNodeReporter;

@TargetApi(SGConfig.UI_FEATURES_MIN_API)
class ViewSnapshot {

    @SuppressWarnings("unused")
    private static final String LOGTAG = "SugoAPI.Snapshot";
    private static final int BUFFER = 10240 ;
    private final RootViewFinder mRootViewFinder;
    private final List<PropertyDescription> mProperties;
    private final ClassNameCache mClassnameCache;
    private final Handler mMainThreadHandler;
    private final ResourceIds mResourceIds;
    private XWalkViewListener mXWalkViewListener;
    private static final int MAX_CLASS_NAME_CACHE_SIZE = 255;

    ViewSnapshot(List<PropertyDescription> properties, ResourceIds resourceIds) {
        mProperties = properties;
        mResourceIds = resourceIds;
        mMainThreadHandler = new Handler(Looper.getMainLooper());
        mRootViewFinder = new RootViewFinder();
        mClassnameCache = new ClassNameCache(MAX_CLASS_NAME_CACHE_SIZE);
    }

    /**
     * Take a snapshot of each activity in liveActivities. The given UIThreadSet will be accessed
     * on the main UI thread, and should contain a set with elements for every activity to be
     * snapshotted. Given stream out will be written on the calling thread.
     */
    void snapshots(UIThreadSet<Activity> liveActivities, OutputStream out, String bitmapHash, boolean shouldCompressed) throws IOException {
        mRootViewFinder.findInActivities(liveActivities);
        final FutureTask<List<RootViewInfo>> infoFuture = new FutureTask<List<RootViewInfo>>(mRootViewFinder);
        mMainThreadHandler.post(infoFuture);

        final OutputStreamWriter writer = new OutputStreamWriter(out);
        List<RootViewInfo> infoList = Collections.emptyList();
        writer.write("[");

        try {
            infoList = infoFuture.get(1, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            if (SGConfig.DEBUG) {
                Log.d(LOGTAG, "Screenshot interrupted, no screenshot will be sent.", e);
            }
        } catch (final TimeoutException e) {
            if (SGConfig.DEBUG) {
                Log.i(LOGTAG, "Screenshot took more than 1 second to be scheduled and executed. No screenshot will be sent.", e);
            }
        } catch (final ExecutionException e) {
            if (SGConfig.DEBUG) {
                Log.e(LOGTAG, "Exception thrown during screenshot attempt", e);
            }
        }

        final int infoCount = infoList.size();
        for (int i = 0; i < infoCount; i++) {
            if (i > 0) {
                writer.write(",");
            }
            final RootViewInfo info = infoList.get(i);
            writer.write("{");
            writer.write("\"activity\":");
            writer.write(JSONObject.quote(info.activityName));
            writer.write(",");
            writer.write("\"scale\":");
            writer.write(String.format("%s", info.scale));
            writer.write(",");

            String imageHash = null;
            if (mXWalkViewListener == null) {
                imageHash = info.screenshot.getBitmapHash();
            } else {
                imageHash = info.screenshot.getRandomHash();
            }
            writer.write("\"image_hash\":");
            writer.write(String.format("\"%s\"", imageHash));
            if (bitmapHash == null || !bitmapHash.equals(imageHash)) {
                writer.write(",");
                Writer soWriter;
                if (shouldCompressed){
                    writer.write("\"compressed_serialized_objects\":\"");
                    soWriter = new StringWriter();
                } else {
                    writer.write("\"serialized_objects\":");
                    soWriter = writer;
                }

                {
                    final JsonWriter j = new JsonWriter(soWriter);
                    j.beginObject();
                    j.name("rootObject").value(info.rootView.hashCode());
                    j.name("objects");
                    snapshotViewHierarchy(j, info.rootView);
                    j.endObject();
                    j.flush();
                }

                if (shouldCompressed){
                    String serializedObjects = ((StringWriter)soWriter).toString();
                    try {
                        String charSet = "UTF-8";
                        byte[] values = compress(serializedObjects.getBytes(charSet));
                       // String str = new String(values,charSet) ;
                        writer.write(io.sugo.android.java_websocket.util.Base64.encodeBytes(values));
                        writer.write("\"");
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                writer.write(",");
                writer.write("\"screenshot\":");
                writer.flush();
                info.screenshot.writeBitmapJSON(Bitmap.CompressFormat.JPEG, 60, out);
            }
            writer.write("}");
        }

        writer.write("]");
        writer.flush();
    }

    public static byte[] compress(byte[] data) throws Exception {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        compress(bais, baos);
        byte[] output = baos.toByteArray();
        baos.flush();
        baos.close();
        bais.close();
        return output;
    }
    public static void compress(InputStream is, OutputStream os)
            throws Exception {
        GZIPOutputStream gos = new GZIPOutputStream(os);
        int count;
        byte data[] = new byte[BUFFER];
        while ((count = is.read(data, 0, BUFFER)) != -1) {
            gos.write(data, 0, count);
        }
        gos.finish();
        gos.flush();
        gos.close();
    }

    // For testing only
    List<PropertyDescription> getProperties() {
        return mProperties;
    }

    private void snapshotViewHierarchy(JsonWriter j, View rootView) throws IOException {
        j.beginArray();
        snapshotView(j, rootView);
        j.endArray();
    }

    private void snapshotView(JsonWriter j, View view) throws IOException {
        if (!SGConfig.isShowInvisibleView() && view.getVisibility() != View.VISIBLE) {
            return;
        }
        // 不处理 ScrollView 未显示的 item
        if (isScrollViewChild(view)) {
            int scrollTop = ((ScrollView) view.getParent().getParent()).getScrollY();
            int scrollviewHeight = ((ScrollView) view.getParent().getParent()).getHeight();
            int viewTop = view.getTop();
            int viewBottom = view.getBottom();
            if (viewTop - scrollTop > scrollviewHeight) {
                return;
            }
            if (scrollTop > viewBottom) {
                return;
            }
        }
        // 不处理 NestedScrollView 未显示的 item
        if (isNestedScrollViewChild(view)) {
            int scrollTop = ((NestedScrollView) view.getParent().getParent()).getScrollY();
            int scrollviewHeight = ((NestedScrollView) view.getParent().getParent()).getHeight();
            int viewTop = view.getTop();
            int viewBottom = view.getBottom();
            if (viewTop - scrollTop > scrollviewHeight) {
                return;
            }
            if (scrollTop > viewBottom) {
                return;
            }
        }
        // 不处理 HorizontalScrollView 未显示的 item
        if (isHorizontalScrollViewChild(view)) {
            int scrollX = ((HorizontalScrollView) view.getParent().getParent()).getScrollX();
            int scrollviewWidth = ((HorizontalScrollView) view.getParent().getParent()).getWidth();
            int viewLeft = view.getLeft();
            int viewRight = view.getRight();
            // item 的最左边，超过了 scroller 的最大宽度，该 item 在 scroller 外边
            if (viewLeft - scrollX > scrollviewWidth) {
                return;
            }
            // item 的最右边，在 scroller 的最左边的外边
            if (scrollX > viewRight) {
                return;
            }
        }

        final int viewId = view.getId();
        final String viewIdName;
        if (-1 == viewId) {
            viewIdName = null;
        } else {
            viewIdName = mResourceIds.nameForId(viewId);
        }

        j.beginObject();
        j.name("hashCode").value(view.hashCode());
        j.name("id").value(viewId);
        j.name("mp_id_name").value(viewIdName);

        final CharSequence description = view.getContentDescription();
        if (null == description) {
            j.name("contentDescription").nullValue();
        } else {
            j.name("contentDescription").value(description.toString());
        }

        final Object tag = view.getTag();
        if (null == tag) {
            j.name("tag").nullValue();
        } else if (tag instanceof CharSequence) {
            j.name("tag").value(tag.toString());
        }

        j.name("top").value(view.getTop());
        j.name("left").value(view.getLeft());
        j.name("width").value(view.getWidth());
        j.name("height").value(view.getHeight());
        j.name("scrollX").value(view.getScrollX());
        j.name("scrollY").value(view.getScrollY());
        j.name("visibility").value(view.getVisibility());

        float translationX = 0;
        float translationY = 0;
        if (Build.VERSION.SDK_INT >= 11) {
            translationX = view.getTranslationX();
            translationY = view.getTranslationY();
        }

        j.name("translationX").value(translationX);
        j.name("translationY").value(translationY);

        j.name("classes");
        j.beginArray();
        Class<?> klass = view.getClass();
        do {
            j.value(mClassnameCache.get(klass));
            klass = klass.getSuperclass();
        } while (klass != Object.class && klass != null);
        j.endArray();

        addProperties(j, view);

        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams relativeLayoutParams = (RelativeLayout.LayoutParams) layoutParams;
            int[] rules = relativeLayoutParams.getRules();
            j.name("layoutRules");
            j.beginArray();
            for (int rule : rules) {
                j.value(rule);
            }
            j.endArray();
        }
        // 因为 Scroller 会优化未显示的 item， 所以要为 Scroller 的 子 View 添加正确的 index
        if (isScrollViewChild(view) || isHorizontalScrollViewChild(view) || isNestedScrollViewChild(view)) {
            int index = ((ViewGroup) view.getParent()).indexOfChild(view);
            int otherTypeCount = getOtherTypeCount(0, index, view, ((ViewGroup) view.getParent()));
            index = index - otherTypeCount;
            j.name("indexOfScroller").value(index);
        }
        if (view instanceof WebView) {
            SugoWebNodeReporter sugoWebNodeReporter = SugoAPI.getSugoWebNodeReporter(view);
            if (sugoWebNodeReporter != null) {
                final WebView webView = (WebView) view;
                int oldVersion = sugoWebNodeReporter.version;
                webView.post(new Runnable() {
                    @Override
                    public void run() {
                        webView.loadUrl("javascript:if(typeof sugo === 'object' && typeof sugo.reportNodes === 'function'){sugo.reportNodes();}");
                    }
                });
                int max_attempt = 10;
                int count = 0;
                while (oldVersion == sugoWebNodeReporter.version && count < max_attempt) {
                    try {
                        count++;
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Log.e(LOGTAG, e.getMessage());
                    }
                }
                if (count < max_attempt) {
                    j.name("htmlPage");
                    j.beginObject();
                    j.name("url").value(sugoWebNodeReporter.url);
                    j.name("clientWidth").value(sugoWebNodeReporter.clientWidth);
                    j.name("clientHeight").value(sugoWebNodeReporter.clientHeight);
                    j.name("nodes").value(sugoWebNodeReporter.webNodeJson);
                    j.name("title").value(sugoWebNodeReporter.title);
                    j.endObject();
                }
            } else {
                Log.v(LOGTAG, "You can call SugoAPI.handlerWebView() before WebView.loadUrl() for snapshot html page");
            }
        }

        if (mXWalkViewListener != null) {
            mXWalkViewListener.snapshotSpecialView(j, view);
        }

        j.name("subviews");
        j.beginArray();
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            final int childCount = group.getChildCount();
            for (int i = 0; i < childCount; i++) {
                final View child = group.getChildAt(i);
                // child can be null when views are getting disposed.
                if (null != child) {
                    j.value(child.hashCode());
                }
            }
        }
        j.endArray();
        j.endObject();

        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            final int childCount = group.getChildCount();
            for (int i = 0; i < childCount; i++) {
                final View child = group.getChildAt(i);
                // child can be null when views are getting disposed.
                if (null != child) {
                    snapshotView(j, child);
                }
            }
        }
    }

    private void addProperties(JsonWriter j, View v) throws IOException {
        final Class<?> viewClass = v.getClass();
        for (final PropertyDescription desc : mProperties) {
            if (desc.targetClass.isAssignableFrom(viewClass) && null != desc.accessor) {
                final Object value = desc.accessor.applyMethod(v);
                if (null == value) {
                    // Don't produce anything in this case
                } else if (value instanceof Number) {
                    j.name(desc.name).value((Number) value);
                } else if (value instanceof Boolean) {
                    if (desc.name.equals("clickable") && ((!(v instanceof ViewGroup) && v.getClass().getName().contains("React"))
                            || v.getClass().getName().contains("ReactViewGroup"))){
                        j.name(desc.name).value(true);
                    }else {
                        j.name(desc.name).value((Boolean) value);
                    }
                } else if (value instanceof ColorStateList) {
                    j.name(desc.name).value((Integer) ((ColorStateList) value).getDefaultColor());
                } else if (value instanceof Drawable) {
                    final Drawable drawable = (Drawable) value;
                    final Rect bounds = drawable.getBounds();
                    j.name(desc.name);
                    j.beginObject();
                    j.name("classes");
                    j.beginArray();
                    Class klass = drawable.getClass();
                    while (klass != Object.class) {
                        j.value(klass.getCanonicalName());
                        klass = klass.getSuperclass();
                    }
                    j.endArray();
                    j.name("dimensions");
                    j.beginObject();
                    j.name("left").value(bounds.left);
                    j.name("right").value(bounds.right);
                    j.name("top").value(bounds.top);
                    j.name("bottom").value(bounds.bottom);
                    j.endObject();
                    if (drawable instanceof ColorDrawable) {
                        final ColorDrawable colorDrawable = (ColorDrawable) drawable;
                        j.name("color").value(colorDrawable.getColor());
                    }
                    j.endObject();
                } else {
                    j.name(desc.name).value(value.toString());
                }
            }
        }
    }

    private boolean isScrollViewChild(View view) {
        if (view.getParent() == null) {
            return false;
        }
        if (view.getParent().getParent() == null) {
            return false;
        }
        ViewParent viewParent = view.getParent().getParent();
        return viewParent instanceof ScrollView;
    }

    private boolean isNestedScrollViewChild(View view) {
        if (view.getParent() == null) {
            return false;
        }
        if (view.getParent().getParent() == null) {
            return false;
        }
        ViewParent viewParent = view.getParent().getParent();
        return viewParent instanceof NestedScrollView;
    }

    private boolean isHorizontalScrollViewChild(View view) {
        if (view.getParent() == null) {
            return false;
        }
        if (view.getParent().getParent() == null) {
            return false;
        }
        ViewParent viewParent = view.getParent().getParent();
        return viewParent instanceof HorizontalScrollView;
    }

    private int getOtherTypeCount(int start, int end, View view, ViewGroup viewParent) {
        String canonicalName = view.getClass().getCanonicalName();
        int viewId = view.getId();
        String contentDesc = view.getContentDescription() == null ? null : view.getContentDescription().toString();
        String viewTag = view.getTag() == null ? null : view.getTag().toString();

        int count = 0;
        for (int i = start; i < end; i++) {
            // 统计不是同类型的兄弟 View 的个数
            View broView = viewParent.getChildAt(i);

            // 类名必须相同
            if (!canonicalName.equals(broView.getClass().getCanonicalName())) {
                count++;
                continue;
            }

            // 如果有 id ，id 必须相同
//            if (viewId != -1 && (viewId != broView.getId())) {
//                count++;
//                continue;
//            }

            // 如果有 contentDesc ，那么必须相同
            if (!TextUtils.isEmpty(contentDesc) && (TextUtils.isEmpty(broView.getContentDescription()))) {
                count++;
                continue;
            }
            if (!TextUtils.isEmpty(contentDesc) && (!contentDesc.equals(broView.getContentDescription()))) {
                count++;
                continue;
            }

            // 如果有 tag ，那么必须相同
            if (!TextUtils.isEmpty(viewTag) && (broView.getTag() == null)) {
                count++;
                continue;
            }
            if (!TextUtils.isEmpty(viewTag) && (!broView.getTag().toString().equals(viewTag))) {
                count++;
                continue;
            }
        }
        return count;
    }

    public void setXWalkViewListener(XWalkViewListener XWalkViewListener) {
        mXWalkViewListener = XWalkViewListener;
    }

    private static class ClassNameCache extends LruCache<Class<?>, String> {
        public ClassNameCache(int maxSize) {
            super(maxSize);
        }

        @Override
        protected String create(Class<?> klass) {
            return klass.getCanonicalName();
        }
    }

    private static class RootViewFinder implements Callable<List<RootViewInfo>> {

        private UIThreadSet<Activity> mLiveActivities;
        private final List<RootViewInfo> mRootViews;
        private final DisplayMetrics mDisplayMetrics;
        private final CachedBitmap mCachedBitmap;
        private final int mClientDensity = DisplayMetrics.DENSITY_DEFAULT;

        public RootViewFinder() {
            mDisplayMetrics = new DisplayMetrics();
            mRootViews = new ArrayList<RootViewInfo>();
            mCachedBitmap = new CachedBitmap();
        }

        public void findInActivities(UIThreadSet<Activity> liveActivities) {
            mLiveActivities = liveActivities;
        }

        @Override
        public List<RootViewInfo> call() throws Exception {
            mRootViews.clear();

            final Set<Activity> liveActivities = mLiveActivities.getAll();

            for (final Activity a : liveActivities) {
                final String activityName = a.getClass().getCanonicalName();
                final View rootView = a.getWindow().getDecorView().getRootView();
                a.getWindowManager().getDefaultDisplay().getMetrics(mDisplayMetrics);
                final RootViewInfo info = new RootViewInfo(activityName, rootView);
                mRootViews.add(info);
            }

            final int viewCount = mRootViews.size();
            for (int i = 0; i < viewCount; i++) {
                final RootViewInfo info = mRootViews.get(i);
                takeScreenshot(info);
            }

            return mRootViews;
        }

        private void takeScreenshot(final RootViewInfo info) {
            final View rootView = info.rootView;
            Bitmap rawBitmap = null;

            try {
                final Method createSnapshot = View.class.getDeclaredMethod("createSnapshot", Bitmap.Config.class, Integer.TYPE, Boolean.TYPE);
                createSnapshot.setAccessible(true);
                rawBitmap = (Bitmap) createSnapshot.invoke(rootView, Bitmap.Config.RGB_565, Color.WHITE, false);
            } catch (final NoSuchMethodException e) {
                if (SGConfig.DEBUG) {
                    Log.v(LOGTAG, "Can't call createSnapshot, will use drawCache", e);
                }
            } catch (final IllegalArgumentException e) {
                Log.d(LOGTAG, "Can't call createSnapshot with arguments", e);
            } catch (final InvocationTargetException e) {
                Log.e(LOGTAG, "Exception when calling createSnapshot", e);
            } catch (final IllegalAccessException e) {
                Log.e(LOGTAG, "Can't access createSnapshot, using drawCache", e);
            } catch (final ClassCastException e) {
                Log.e(LOGTAG, "createSnapshot didn't return a bitmap?", e);
            }

            Boolean originalCacheState = null;
            try {
                if (null == rawBitmap) {
                    originalCacheState = rootView.isDrawingCacheEnabled();
                    rootView.setDrawingCacheEnabled(true);
                    rootView.buildDrawingCache(true);
                    rawBitmap = rootView.getDrawingCache();
                }
            } catch (final RuntimeException e) {
                if (SGConfig.DEBUG) {
                    Log.v(LOGTAG, "Can't take a bitmap snapshot of view " + rootView + ", skipping for now.", e);
                }
            }

            float scale = 1.0f;
            if (null != rawBitmap) {
                final int rawDensity = rawBitmap.getDensity();

                if (rawDensity != Bitmap.DENSITY_NONE) {
                    scale = ((float) mClientDensity) / rawDensity;
                }

                final int rawWidth = rawBitmap.getWidth();
                final int rawHeight = rawBitmap.getHeight();
                final int destWidth = (int) ((rawBitmap.getWidth() * scale) + 0.5);
                final int destHeight = (int) ((rawBitmap.getHeight() * scale) + 0.5);

                if (rawWidth > 0 && rawHeight > 0 && destWidth > 0 && destHeight > 0) {
                    mCachedBitmap.recreate(destWidth, destHeight, mClientDensity, rawBitmap);
                }
            }

            if (null != originalCacheState && !originalCacheState) {
                rootView.setDrawingCacheEnabled(false);
            }
            info.scale = scale;
            info.screenshot = mCachedBitmap;
        }

    }

    private static class CachedBitmap {
        public CachedBitmap() {
            mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
            mCached = null;
        }

        public synchronized void recreate(int width, int height, int destDensity, Bitmap source) {
            if (null == mCached || mCached.getWidth() != width || mCached.getHeight() != height) {
                try {
                    mCached = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
                } catch (final OutOfMemoryError e) {
                    mCached = null;
                }

                if (null != mCached) {
                    mCached.setDensity(destDensity);
                }

            }

            if (null != mCached) {
                final Canvas scaledCanvas = new Canvas(mCached);
                scaledCanvas.drawBitmap(source, 0, 0, mPaint);
            }
        }

        // Writes a QUOTED base64 string (or the string null) to the output stream
        public synchronized void writeBitmapJSON(Bitmap.CompressFormat format, int quality, OutputStream out)
                throws IOException {
            if (null == mCached || mCached.getWidth() == 0 || mCached.getHeight() == 0) {
                out.write("null".getBytes());
            } else {
                out.write('"');
                final Base64OutputStream imageOut = new Base64OutputStream(out, Base64.NO_WRAP);
                mCached.compress(format, quality, imageOut);
                imageOut.flush();
                out.write('"');
            }
        }

        public synchronized String getBitmapHash() {
            if(mCached == null){
                return "";
            }
            String mCachedHash = null;
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            mCached.compress(Bitmap.CompressFormat.PNG, 100, stream);
            byte[] byteArray = stream.toByteArray();
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                md.update(byteArray);
                byte messageDigest[] = md.digest();

                // Create Hex String
                StringBuffer hexString = new StringBuffer();
                for (int i = 0; i < messageDigest.length; i++)
                    hexString.append(Integer.toHexString(0xFF & messageDigest
                            [i]));
                mCachedHash = hexString.toString();
            } catch (NoSuchAlgorithmException e) {
                mCachedHash = null;
            }
            return mCachedHash;
        }

        public synchronized String getRandomHash() {
            String mCachedHash = null;
            Random random = new Random();
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                md.update((random.nextInt(10000000) + "'").getBytes());
                byte messageDigest[] = md.digest();

                // Create Hex String
                StringBuffer hexString = new StringBuffer();
                for (int i = 0; i < messageDigest.length; i++) {
                    hexString.append(Integer.toHexString(0xFF & messageDigest[i]));
                }
                mCachedHash = hexString.toString();
            } catch (NoSuchAlgorithmException e) {
                mCachedHash = null;
            }
            return mCachedHash;
        }

        private Bitmap mCached;
        private final Paint mPaint;
    }

    private static class RootViewInfo {
        public RootViewInfo(String activityName, View rootView) {
            this.activityName = activityName;
            this.rootView = rootView;
            this.screenshot = null;
            this.scale = 1.0f;
        }

        public final String activityName;
        public final View rootView;
        public CachedBitmap screenshot;
        public float scale;
    }

}
