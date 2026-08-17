package it.paolo.passwordsafe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class SecurityChartView(context: Context) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var values=intArrayOf(0,0,0)
    fun setValues(strong:Int,weak:Int,reused:Int){values=intArrayOf(strong,weak,reused);invalidate()}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        val size=width.coerceAtMost(height)-48f
        val oval=RectF(28f,(height-size)/2f,28f+size,(height+size)/2f)
        val total=values.sum().coerceAtLeast(1);var start=-90f
        val colors=intArrayOf(Color.rgb(121,166,26),Color.rgb(205,38,38),Color.rgb(170,170,170))
        if(values.sum()==0){paint.color=Color.rgb(70,95,110);canvas.drawArc(oval,0f,360f,true,paint);return}
        values.forEachIndexed{i,value->if(value>0){val sweep=360f*value/total;paint.color=colors[i];canvas.drawArc(oval,start,sweep,true,paint);start+=sweep}}
        paint.color=Color.rgb(17,38,55);canvas.drawCircle(oval.centerX(),oval.centerY(),size*.25f,paint)
    }
}
