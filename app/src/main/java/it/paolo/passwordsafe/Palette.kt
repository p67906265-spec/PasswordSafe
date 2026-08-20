package it.paolo.passwordsafe

import android.graphics.Color

/** Identità visiva Password Safe: navy profondo + ciano elettrico. */
object Palette {
    val INK = Color.rgb(2, 11, 22)
    val SURFACE = Color.rgb(5, 24, 38)
    val SURFACE_2 = Color.rgb(8, 34, 50)
    val BORDER = Color.rgb(13, 78, 91)

    val CYAN = Color.rgb(0, 212, 228)
    val CYAN_SOFT = Color.rgb(103, 234, 241)
    val CYAN_DARK = Color.rgb(0, 151, 167)

    // Alias per mantenere coerenti anche le schermate che usavano i vecchi nomi.
    val IRIS = CYAN
    val IRIS_SOFT = CYAN_SOFT
    val GOLD = CYAN

    val CORAL = Color.rgb(255, 107, 92)
    val SUCCESS = Color.rgb(94, 196, 140)
    val TEXT = Color.rgb(247, 253, 255)
    val TEXT_DIM = Color.rgb(166, 194, 204)
}
