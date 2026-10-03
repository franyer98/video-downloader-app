package com.franyer.descargavideos

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** Pantalla invisible: la abre la notificación de actualización y lanza el instalador. */
class InstalarActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nueva = Actualizador.descargada(this)
        if (nueva != null) Actualizador.instalar(this, nueva)
        else Toast.makeText(this, "Ya tienes la última versión", Toast.LENGTH_SHORT).show()
        finish()
    }
}
