package com.franyer.descargavideos

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Capa que envuelve al reproductor y detecta toques rápidos:
 * doble toque a la izquierda = atrás, a la derecha = adelante.
 * Después de un doble toque, cada toque extra en el mismo lado suma otro salto.
 * Los toques normales siguen llegando al reproductor (mostrar controles, pausa…).
 */
class ZonaToques @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null,
) : FrameLayout(ctx, attrs) {

    /** -1 = atrás, +1 = adelante. */
    var alSaltar: ((direccion: Int) -> Unit)? = null

    private var ladoActivo = 0
    private var ultimoSalto = 0L
    private val ventanaSeguidaMs = 700L

    private val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val lado = lado(e.x)
            if (lado != 0) saltar(lado)
            return lado != 0
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Toques seguidos tras un doble toque: siguen saltando en el mismo lado
            val lado = lado(e.x)
            if (lado != 0 && lado == ladoActivo && System.currentTimeMillis() - ultimoSalto < ventanaSeguidaMs) {
                saltar(lado)
                return true
            }
            return false
        }
    })

    /** Tercio izquierdo = atrás, tercio derecho = adelante, centro = nada. */
    private fun lado(x: Float): Int = when {
        x < width / 3f -> -1
        x > width * 2 / 3f -> 1
        else -> 0
    }

    private fun saltar(lado: Int) {
        ladoActivo = lado
        ultimoSalto = System.currentTimeMillis()
        alSaltar?.invoke(lado)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
}
