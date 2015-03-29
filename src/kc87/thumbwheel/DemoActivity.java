package kc87.thumbwheel;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

public class DemoActivity extends Activity
{
   @Override
   public void onCreate(Bundle savedInstanceState)
   {
      super.onCreate(savedInstanceState);
      setContentView(R.layout.main);
      setup();
   }

   private void setup()
   {
      final TextView valueText1 = (TextView)findViewById(R.id.wheel_1_value);
      final TextView valueText2 = (TextView)findViewById(R.id.wheel_2_value);
      final ThumbWheel thumbWheel1 = (ThumbWheel)findViewById(R.id.wheel_1);
      final ThumbWheel thumbWheel2 = (ThumbWheel)findViewById(R.id.wheel_2);

      valueText1.setText(String.format("%d",(int)thumbWheel1.getValue()));
      valueText2.setText(String.format("%d",(int)thumbWheel2.getValue()));

      thumbWheel1.setOnValueChangedListener(new ThumbWheel.OnValueChangeListener()
      {
         @Override
         public void onValueChanged(View v, float value)
         {
            valueText1.setText(String.format("%d", (int) value));
         }
      });

      thumbWheel2.setOnValueChangedListener(new ThumbWheel.OnValueChangeListener()
      {
         @Override
         public void onValueChanged(View v, float value)
         {
            valueText2.setText(String.format("%d", (int) value));
         }
      });
   }
}
