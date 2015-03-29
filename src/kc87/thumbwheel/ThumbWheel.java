package kc87.thumbwheel;

import java.lang.Math;
import java.lang.Thread;
import java.util.concurrent.atomic.AtomicInteger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.TextureView;
import android.view.TextureView.SurfaceTextureListener;
import android.view.MotionEvent;
import android.graphics.SurfaceTexture;

import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;

import static android.opengl.EGL14.*;
import static android.opengl.GLES10.*;

import android.content.Context;

public class ThumbWheel extends TextureView implements SurfaceTextureListener
{
   private static final String LOG_TAG = "ThumbWheel";
   private static final int DEFAULT_WHEEL_DIAMETER = 100;
   private static final float WHEEL_THICKNESS_RATIO = 0.25f;
   private static final float FPS = 60.0f;

   // Default values for custom xml attributes
   private static final float DEFAULT_MIN_VALUE = 0.0f;
   private static final float DEFAULT_MAX_VALUE = 100.0f;
   private static final float DEFAULT_RATIO = 1.0f;
   private static final int DEFAULT_ORIENTATION = Orientation.HORIZONTAL;
   private static final int DEFAULT_BOUNDARY_MODE = BoundaryMode.REPEAT;

   // GL material parameters
   private static final float matDBlue[] = new float[]{0.0f, 0.2f, 0.8f, 0.0f};
   private static final float matSBlue[] = new float[]{0.0f, 0.0f, 0.2f, 0.0f};
   private static final float matDRed[] = new float[]{0.4f, 0.0f, 0.0f, 0.0f};
   private static final float matSRed[] = new float[]{0.6f, 0.0f, 0.0f, 0.0f};

   private static final float matDGray[] = new float[]{0.5f, 0.5f, 0.5f, 0.0f};
   private static final float matSGray[] = new float[]{0.1f, 0.1f, 0.1f, 0.0f};
   private static final float matDWhite[] = new float[]{0.3f, 0.3f, 0.3f, 0.0f};
   private static final float matSWhite[] = new float[]{0.8f, 0.8f, 0.8f, 0.0f};

   // GL light source parameters
   private static final float lightPosition[] = new float[]{0.0f, 0.0f, 10.0f, 0.0f};
   private static final float lightSColor[] = new float[]{1.0f, 1.0f, 1.0f, 0.0f};
   private static final float lightDColor[] = new float[]{1.0f, 1.0f, 1.0f, 0.0f};
   private static final float matShininess[] = new float[]{100.0f};
   // GL geometry
   private static FloatBuffer sVertexNormalBuffer;
   private static final float[] sVertexNormal = new float[2 * 7 * 6 * 16 * 3];

   static {
      initGeometry();
   }

   public class Orientation
   {
      public static final int HORIZONTAL = 0;
      public static final int VERTICAL = 1;
   }

   public class BoundaryMode
   {
      public static final int REPEAT = 0;
      public static final int CLAMP = 1;
   }

   private static class STATE
   {
      public static final int IDLE = 0;
      public static final int TOUCHED = 1;
      public static final int DRAGGED = 2;
      public static final int RELEASED = 3;
      public static final int IN_MOTION = 4;
   }

   private float mMatDefInner[];
   private float mMatSpecInner[];
   private float mMatDefOuter[];
   private float mMatSpecOuter[];

   private GLRenderThread mRenderThread;
   private OnValueChangeListener mListener;
   private int mOrientation = DEFAULT_ORIENTATION;
   private int mBoundaryMode = DEFAULT_BOUNDARY_MODE;
   private float mMinValue = DEFAULT_MIN_VALUE;
   private float mMaxValue = DEFAULT_MAX_VALUE;
   private float mRatio = DEFAULT_RATIO;
   private volatile float mValue = DEFAULT_MIN_VALUE;
   private float mDensity = 1.0f;
   private int mSkipCounter = 2;


   public ThumbWheel(final Context context)
   {
      this(context, null);
   }

   public ThumbWheel(final Context context, AttributeSet attrs)
   {
      super(context, attrs);
      mDensity = getResources().getDisplayMetrics().density;

      if(attrs != null)
      {
         TypedArray typedArray = context.getTheme().obtainStyledAttributes(attrs,R.styleable.ThumbWheel,0,0);
         mMinValue = typedArray.getFloat(R.styleable.ThumbWheel_minValue,DEFAULT_MIN_VALUE);
         mMaxValue = typedArray.getFloat(R.styleable.ThumbWheel_maxValue,DEFAULT_MAX_VALUE);
         mOrientation = typedArray.getInt(R.styleable.ThumbWheel_orientation,DEFAULT_ORIENTATION);
         mBoundaryMode = typedArray.getInt(R.styleable.ThumbWheel_boundaryMode,DEFAULT_BOUNDARY_MODE);
         mValue = mMinValue;
      }

      /*
      mMatDefInner = matDRed;
      mMatSpecInner = matSRed;
      mMatDefOuter = matDBlue;
      mMatSpecOuter = matSBlue;
      */
      mMatDefInner = matDWhite;
      mMatSpecInner = matSWhite;
      mMatDefOuter = matDGray;
      mMatSpecOuter = matSGray;

      mRenderThread = new GLRenderThread();
      setSurfaceTextureListener(this);
   }

   public float getValue()
   {
      return mValue;
   }

   public void setOrientation(final int orientation)
   {
      mOrientation = orientation&1;
   }

   public void setOnValueChangedListener(final OnValueChangeListener listener)
   {
      mListener = listener;
   }

   public void setRange(final float min, final float max)
   {
      mMinValue = min / mRatio;
      mMaxValue = max / mRatio;
   }

   public void setRatio(final float ratio)
   {
      mRatio = ratio;
      setRange(mMinValue, mMaxValue);
   }

   public void setBoundaryMode(final int mode)
   {
      mBoundaryMode = mode&1;
   }

   /*
    * Parent has size constraints:
    * MeasureSpec.EXACTLY     => X px or MATCH_PARENT
    * MeasureSpec.AT_MOST     => WRAP_CONTENT
    * Parent has no size constraints:
    * MeasureSpec.UNSPECIFIED => WRAP_CONTENT
    */

   @Override
   public void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
   {
      super.onMeasure(widthMeasureSpec,heightMeasureSpec);

      float wheelWidth;
      float wheelHeight;

      final int specModeW = MeasureSpec.getMode(widthMeasureSpec);
      final int specModeH = MeasureSpec.getMode(heightMeasureSpec);
      final int specWidth = MeasureSpec.getSize(widthMeasureSpec);
      final int specHeight = MeasureSpec.getSize(heightMeasureSpec);

      Log.d(LOG_TAG,"onMeasure() spec width -> "+specModeToString(specModeW)+":"+specWidth+
                                " spec height -> "+specModeToString(specModeH)+":"+specHeight);


      if ((specModeW == MeasureSpec.EXACTLY) && (specModeH == MeasureSpec.EXACTLY)) {
         setMeasuredDimension(specWidth, specHeight);
         return;
      }

      if (mOrientation == Orientation.HORIZONTAL) {

         if (specModeW == MeasureSpec.EXACTLY || specModeW == MeasureSpec.AT_MOST) {
            wheelWidth = specWidth;
         } else {
            wheelWidth = mDensity * DEFAULT_WHEEL_DIAMETER;
         }

         wheelHeight = (specModeH == MeasureSpec.EXACTLY) ? specHeight : WHEEL_THICKNESS_RATIO * wheelWidth;

         if (specModeH == MeasureSpec.AT_MOST) {
            wheelHeight = Math.min(wheelHeight, specHeight);
         }

      } else {

         // Make it as high as allowed
         if (specModeH == MeasureSpec.EXACTLY || specModeH == MeasureSpec.AT_MOST) {
            wheelHeight = specHeight;
         } else {
            // No limits -> use default height
            wheelHeight = mDensity * DEFAULT_WHEEL_DIAMETER;
         }

         wheelWidth = (specModeW == MeasureSpec.EXACTLY) ? specWidth : WHEEL_THICKNESS_RATIO * wheelHeight;

         if (specModeW == MeasureSpec.AT_MOST) {
            wheelWidth = Math.min(wheelWidth, specWidth);
         }
      }

      Log.d(LOG_TAG,"onMeasure() results -> width:"+(int)wheelWidth+" height:"+(int)wheelHeight);
      setMeasuredDimension((int) wheelWidth, (int) wheelHeight);
   }

   @Override
   public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height)
   {
      mRenderThread.mmSurfaceTexture = surface;
      mRenderThread.mmWidth = width;
      mRenderThread.mmHeight = height;
      mRenderThread.start();
   }

   @Override
   public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height)
   {
      Log.d(LOG_TAG, "onSurfaceTextureSizeChange()");
   }

   @Override
   public boolean onSurfaceTextureDestroyed(SurfaceTexture surface)
   {
      if (mRenderThread != null) {
         mRenderThread.stopRender();
      }
      return true;
   }

   @Override
   public void onSurfaceTextureUpdated(SurfaceTexture surface)
   {
      if (mListener != null && --mSkipCounter == 0) {
         mListener.onValueChanged(this, mRatio * mValue);
         mSkipCounter = 4;
      }
   }

   @Override
   protected void onAttachedToWindow()
   {
      Log.d(LOG_TAG, "onAttachedToWindow()");
      super.onAttachedToWindow();
   }

   @Override
   protected void onDetachedFromWindow()
   {
      Log.d(LOG_TAG, "onDetachedFromWindow()");
      super.onDetachedFromWindow();
   }

   @Override
   public boolean onTouchEvent(MotionEvent event)
   {
      mRenderThread.mmCurrent = (mOrientation == Orientation.HORIZONTAL) ? -event.getX() : event.getY();

      switch (event.getAction()) {
         case MotionEvent.ACTION_DOWN:
            mRenderThread.mmWheelState.set(STATE.TOUCHED);
            break;
         case MotionEvent.ACTION_MOVE:
            mRenderThread.mmWheelState.set(STATE.DRAGGED);
            break;
         case MotionEvent.ACTION_UP:
            mRenderThread.mmWheelState.set(STATE.RELEASED);
            break;
         default:
            mRenderThread.mmWheelState.set(STATE.IDLE);
            break;
      }

      return true;
   }

   /*
    *   Calculate wheel geometry including vertex normals.
    *   This only happens once, when the class is loaded.
    */

   private static void initGeometry()
   {
      float cos, sin;
      final float dAlpha = 3.1415926f / 8.0f;
      final float d = 0.15f;
      final float r = 0.97f;
      final float t = 0.95f;
      final float f = 0.97f;

      int s1 = 2 * 18, s2 = 2 * 6 * 18;

      for (int n = 0, m = 3 * 2 * 96; n < 16; n++) {
         final float x1 = f * (float) Math.cos((float) n * dAlpha);
         final float x2 = f * (float) Math.cos((float) (n + 1) * dAlpha);
         final float y1 = (t - d);
         final float z1 = f * (float) Math.sin((float) n * dAlpha);
         final float z2 = f * (float) Math.sin((float) (n + 1) * dAlpha);
         final float x11 = f * r * (float) Math.cos(((float) n + d) * dAlpha);
         final float x22 = f * r * (float) Math.cos(((float) n + t - d) * dAlpha);
         final float y11 = (y1 - d);
         final float z11 = f * r * (float) Math.sin(((float) n + d) * dAlpha);
         final float z22 = f * r * (float) Math.sin(((float) n + t - d) * dAlpha);

         addVertexNormal(s1 * n, x11, -y11, z11, x11, 0.0f, z11);
         addVertexNormal(s1 * n + 6, x11, y11, z11, x11, 0.0f, z11);
         addVertexNormal(s1 * n + 12, x22, -y11, z22, x22, 0.0f, z22);
         addVertexNormal(s1 * n + 18, x11, y11, z11, x11, 0.0f, z11);
         addVertexNormal(s1 * n + 24, x22, y11, z22, x22, 0.0f, z22);
         addVertexNormal(s1 * n + 30, x22, -y11, z22, x22, 0.0f, z22);

         addVertexNormal(m + s2 * n, x1, -y1, z1, x1, 1.0f, z1);
         addVertexNormal(m + s2 * n + 6, x11, -y11, z11, x1, 1.0f, z1);
         addVertexNormal(m + s2 * n + 12, x2, -y1, z2, x1, 1.0f, z1);
         addVertexNormal(m + s2 * n + 18, x11, -y11, z11, x1, 1.0f, z1);
         addVertexNormal(m + s2 * n + 24, x22, -y11, z22, x1, 1.0f, z1);
         addVertexNormal(m + s2 * n + 30, x2, -y1, z2, x1, 1.0f, z1);

         addVertexNormal(m + s2 * n + 36, x11, y11, z11, x1, -1.0f, z1);
         addVertexNormal(m + s2 * n + 42, x1, y1, z1, x1, -1.0f, z1);
         addVertexNormal(m + s2 * n + 48, x22, y11, z22, x1, -1.0f, z1);
         addVertexNormal(m + s2 * n + 54, x1, y1, z1, x1, -1.0f, z1);
         addVertexNormal(m + s2 * n + 60, x2, y1, z2, x1, -1.0f, z1);
         addVertexNormal(m + s2 * n + 66, x22, y11, z22, x1, -1.0f, z1);

         cos = (float) Math.cos(n * dAlpha + 0.5f);
         sin = (float) Math.sin(n * dAlpha + 0.5f);

         addVertexNormal(m + s2 * n + 72, x1, -y1, z1, cos, 0.0f, sin);
         addVertexNormal(m + s2 * n + 78, x1, y1, z1, cos, 0.0f, sin);
         addVertexNormal(m + s2 * n + 84, x11, -y11, z11, cos, 0.0f, sin);
         addVertexNormal(m + s2 * n + 90, x1, y1, z1, cos, 0.0f, sin);
         addVertexNormal(m + s2 * n + 96, x11, y11, z11, cos, 0.0f, sin);
         addVertexNormal(m + s2 * n + 102, x11, -y11, z11, cos, 0.0f, sin);

         cos = (float) Math.cos(n * dAlpha - 0.5f);
         sin = (float) Math.sin(n * dAlpha - 0.5f);

         addVertexNormal(m + s2 * n + 108, x2, -y1, z2, cos, 0.0f, sin);
         addVertexNormal(m + s2 * n + 114, x22, -y11, z22, cos, 0.0f, sin);
         addVertexNormal(m + s2 * n + 120, x2, y1, z2, cos, 0.0f, sin);
         addVertexNormal(m + s2 * n + 126, x22, -y11, z22, cos, 0.0f, sin);
         addVertexNormal(m + s2 * n + 132, x22, y11, z22, cos, 0.0f, sin);
         addVertexNormal(m + s2 * n + 138, x2, y1, z2, cos, 0.0f, sin);

         addVertexNormal(m + s2 * n + 144, x1, y1, z1, x1, 0.0f, z1);
         addVertexNormal(m + s2 * n + 150, x1, t, z1, x1, 0.0f, z1);
         addVertexNormal(m + s2 * n + 156, x2, y1, z2, x2, 0.0f, z2);
         addVertexNormal(m + s2 * n + 162, x1, t, z1, x1, 0.0f, z1);
         addVertexNormal(m + s2 * n + 168, x2, t, z2, x2, 0.0f, z2);
         addVertexNormal(m + s2 * n + 174, x2, y1, z2, x2, 0.0f, z2);

         addVertexNormal(m + s2 * n + 180, x1, -t, z1, x1, 0.0f, z1);
         addVertexNormal(m + s2 * n + 186, x1, -y1, z1, x1, 0.0f, z1);
         addVertexNormal(m + s2 * n + 192, x2, -t, z2, x2, 0.0f, z2);
         addVertexNormal(m + s2 * n + 198, x1, -y1, z1, x1, 0.0f, z1);
         addVertexNormal(m + s2 * n + 204, x2, -y1, z2, x2, 0.0f, z2);
         addVertexNormal(m + s2 * n + 210, x2, -t, z2, x2, 0.0f, z2);
      }

      ByteBuffer vertexNormalBuffer = ByteBuffer.allocateDirect(4 * sVertexNormal.length);

      vertexNormalBuffer.order(ByteOrder.nativeOrder()); // Use native byte order

      sVertexNormalBuffer = vertexNormalBuffer.asFloatBuffer(); // Convert byte buffer to float
      sVertexNormalBuffer.put(sVertexNormal);      // Copy data into buffer
   }

   private static void addVertexNormal(int i, float vx, float vy, float vz, float nx, float ny, float nz)
   {
      sVertexNormal[i] = vx;
      sVertexNormal[i + 1] = vy;
      sVertexNormal[i + 2] = vz;
      sVertexNormal[i + 3] = nx;
      sVertexNormal[i + 4] = ny;
      sVertexNormal[i + 5] = nz;
   }

   private class GLRenderThread extends Thread
   {
      private static final String LOG_TAG = "GLRenderThread";
      private static final int DELAY = (int) (1000.0f / FPS);
      private static final float VALUE_RESOLUTION_FACTOR = 0.4f;

      private volatile boolean mmRunning = true;
      private SurfaceTexture mmSurfaceTexture;
      private EGLDisplay mmDisplay = EGL_NO_DISPLAY;
      private EGLContext mmContext = EGL_NO_CONTEXT;
      private EGLSurface mmSurface = EGL_NO_SURFACE;

      private int mmCurrentState = STATE.IDLE;
      private int mmWidth = 0;
      private int mmHeight = 0;

      private float mmLast = 0.0f;
      private float mmDelta = 0.0f;
      private float mmRotation = 0.0f;

      private AtomicInteger mmWheelState = new AtomicInteger();
      //private volatile float mmValue = 0.0f;
      private volatile float mmCurrent = 0.0f;

      private void initEGL()
      {
         final EGLConfig[] configs = new EGLConfig[1];
         final int[] numConfigs = new int[1];
         final int[] version = new int[2];
         final int[] configAttrs = {
                         EGL_ALPHA_SIZE, 8,
                         EGL_BLUE_SIZE, 8,
                         EGL_GREEN_SIZE, 8,
                         EGL_RED_SIZE, 8,
                         EGL_DEPTH_SIZE, 16,
                         EGL_STENCIL_SIZE, 0,
                         EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                         EGL_NONE
         };

         final int[] attr_list = {
                 EGL_NONE
         };

         final int[] surfaceAttrs = {
                 EGL_NONE
         };

         // Get an EGL display connection
         mmDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
         // Initialize the EGL display connection
         eglInitialize(mmDisplay, version, 0, version, 1);
         // Get an appropriate EGL frame buffer configuration
         eglChooseConfig(mmDisplay, configAttrs, 0, configs, 0, configs.length, numConfigs, 0);
         // Create an EGL rendering context
         mmContext = eglCreateContext(mmDisplay, configs[0], EGL_NO_CONTEXT, attr_list, 0);
         // Create an EGL window surface
         mmSurface = eglCreateWindowSurface(mmDisplay, configs[0], mmSurfaceTexture, surfaceAttrs, 0);
         // Connect the context to the surface
         eglMakeCurrent(mmDisplay, mmSurface, mmSurface, mmContext);
      }


      private void initGL()
      {
         glViewport(0, 0, mmWidth, mmHeight);
         glMatrixMode(GL_PROJECTION);
         glLoadIdentity();
         glOrthof(-1.0f, 1.0f, -1.0f, 1.0f, -1.5f, 1.0f);
         glMatrixMode(GL_MODELVIEW);

         glEnableClientState(GL_NORMAL_ARRAY);
         glEnableClientState(GL_VERTEX_ARRAY);

         sVertexNormalBuffer.position(0);
         glVertexPointer(3, GL_FLOAT, 6 * 4, sVertexNormalBuffer);
         sVertexNormalBuffer.position(3);
         glNormalPointer(GL_FLOAT, 6 * 4, sVertexNormalBuffer);

         glLightfv(GL_LIGHT0, GL_POSITION, lightPosition, 0);
         glLightfv(GL_LIGHT0, GL_DIFFUSE, lightDColor, 0);
         glLightfv(GL_LIGHT0, GL_SPECULAR, lightSColor, 0);

         glMaterialfv(GL_FRONT_AND_BACK, GL_SHININESS, matShininess, 0);

         glEnable(GL_DEPTH_TEST);
         glEnable(GL_LIGHTING);
         glEnable(GL_LIGHT0);
         glEnable(GL_NORMALIZE);
         glShadeModel(GL_SMOOTH);
         glEnable(GL_CULL_FACE);
         glCullFace(GL_BACK);
         glFrontFace(GL_CCW);
         glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

         drawFrame();
      }

      private float repeatValue(final float x, final float min, final float max)
      {
         return x > max ? min : x < min ? max : x;
      }

      private float clampValue(final float x, final float min, final float max)
      {
         return x > max ? max : x < min ? min : x;
      }

      private void drawFrame()
      {
         if (mBoundaryMode == BoundaryMode.REPEAT) {
            mValue = repeatValue(mValue + VALUE_RESOLUTION_FACTOR* mmDelta, mMinValue, mMaxValue);
            mmRotation += mmDelta;
         } else if (mBoundaryMode == BoundaryMode.CLAMP){
            mValue = clampValue(mValue + VALUE_RESOLUTION_FACTOR* mmDelta, mMinValue, mMaxValue);
            if (mValue == mMaxValue || mValue == mMinValue) {
               mmDelta = -mmDelta;
            } else {
               mmRotation += mmDelta;
            }
         }

         glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
         glLoadIdentity();

         if (mOrientation == Orientation.HORIZONTAL) {
            glRotatef(mmRotation, 0.0f, 1.0f, 0.0f);
         } else if (mOrientation == Orientation.VERTICAL) {
            glRotatef(90.0f, 0.0f, 0.0f, 1.0f);
            glRotatef(mmRotation, 0.0f, 1.0f, 0.0f);
         }

         // Outer ring
         glMaterialfv(GL_FRONT_AND_BACK, GL_DIFFUSE, mMatDefOuter, 0);
         glMaterialfv(GL_FRONT_AND_BACK, GL_SPECULAR, mMatSpecOuter, 0);
         glDrawArrays(GL_TRIANGLES, 6 * 16, 6 * 6 * 16);

         // Inner ring
         glMaterialfv(GL_FRONT_AND_BACK, GL_DIFFUSE, mMatDefInner, 0);
         glMaterialfv(GL_FRONT_AND_BACK, GL_SPECULAR, mMatSpecInner, 0);
         glDrawArrays(GL_TRIANGLES, 0, 6 * 16);

         eglSwapBuffers(mmDisplay, mmSurface);
      }

      @Override
      public void run()
      {
         initEGL();
         initGL();

         /*
          *    Render loop
          */
         while (mmRunning && !Thread.interrupted()) {
            mmCurrentState = mmWheelState.get();
            if (mmCurrentState == STATE.TOUCHED) {
               mmLast = mmCurrent;
               mmWheelState.set(STATE.IDLE);
            } else if (mmCurrentState == STATE.DRAGGED) {
               int s = (mOrientation == Orientation.HORIZONTAL) ? mmWidth : mmHeight;
               mmDelta = 100.0f * (mmLast - mmCurrent) / s;
               mmLast = mmCurrent;
               drawFrame();
               mmWheelState.set(STATE.IDLE);
            } else if (mmCurrentState == STATE.RELEASED) {
               mmDelta = Math.min(Math.max(mmDelta, -80.0f), 80.0f);
               mmWheelState.set(STATE.IN_MOTION);
            } else if (mmCurrentState == STATE.IN_MOTION) {
               if (Math.abs(mmDelta) > 0.1f) {
                  //FIXME: not yet perfect
                  mmDelta *= 0.85;
                  drawFrame();
                  try {
                     sleep(DELAY);
                  } catch (InterruptedException e) {
                     /* IGNORED */
                  }
               } else {
                  mmWheelState.set(STATE.IDLE);
               }
            } else {
               try {
                  sleep(DELAY);
               } catch (InterruptedException e) {
                  /* IGNORED */
               }
            }
         }

         eglDestroyContext(mmDisplay, mmContext);
         eglDestroySurface(mmDisplay, mmSurface);
         mmContext = EGL_NO_CONTEXT;
         mmSurface = EGL_NO_SURFACE;
      }

      void stopRender()
      {
         interrupt();
         mmRunning = false;
      }
   }

   public static interface OnValueChangeListener
   {
      public void onValueChanged(View v, float value);
   }

   private String specModeToString(int mode)
   {
      switch (mode) {
         case MeasureSpec.EXACTLY:
            return "EXACTLY";
         case MeasureSpec.UNSPECIFIED:
            return "UNSPECIFIED";
         case MeasureSpec.AT_MOST:
            return "AT_MOST";
         default:
            return "???";
      }
   }

}
