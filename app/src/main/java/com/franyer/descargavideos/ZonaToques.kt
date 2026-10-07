package com.franyer.descargavideos

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Capa que envuelve al reproductor: arrastrar el dedo a lo ancho de la pantalla
 * adelanta (hacia la derecha) o atrasa (hacia la izquierda).
 * Los toques normales siguen llegando al reproductor (mostrar controles, pausa…).
 */
class ZonaToques @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null,
) : FrameLayout(ctx, attrs) {

    /** Empezó un arrastre horizontal. */
    var alEmpezar: (() -> Unit)? = null
    /** Fracción del ancho recorrida desde el inicio (-1 a 1, positivo = derecha). */
    var alArrastrar: ((fraccion: Float) -> Unit)? = null
    /** Se soltó el dedo. */
    var alSoltar: (() -> Unit)? = null

    private val umbral = ViewConfiguration.get(ctx).scaledTouchSlop * 2
    private var xInicio = 0f
    private var yInicio = 0f
    private var arrastrando = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                xInicio = ev.x
                yInicio = ev.y
                arrastrando = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - xInicio
                val dy = ev.y - yInicio
                // Solo movimientos claramente horizontales; así el toque normal sigue funcionando
                if (!arrastrando && abs(dx) > umbral && abs(dx) > abs(dy) * 1.5f) {
                    arrastrando = true
                    xInicio = ev.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                    alEmpezar?.invoke()
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!arrastrando) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> alArrastrar?.invoke(((ev.x - xInicio) / width.coerceAtLeast(1)).coerceIn(-1f, 1f))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                arrastrando = false
                alSoltar?.invoke()
            }
        }
        return true
    }
}
