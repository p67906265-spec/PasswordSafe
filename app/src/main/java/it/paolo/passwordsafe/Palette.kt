package it.paolo.passwordsafe

import android.graphics.Color

/**
 * Identità visiva unica dell'app: un solo tema scuro applicato a tutte le schermate
 * (accesso, elenco, editor, impostazioni), con l'oro del quadrante della cassaforte
 * come accento ricorrente e il viola Iris per le azioni primarie.
 */
object Palette {
    val INK = Color.rgb(20, 19, 43)          // sfondo di base, ovunque
    val SURFACE = Color.rgb(33, 31, 75)      // card, righe, intestazioni
    val SURFACE_2 = Color.rgb(42, 40, 112)   // campi di testo, bordi, cerchi
    val IRIS = Color.rgb(111, 95, 234)       // azione primaria
    val IRIS_SOFT = Color.rgb(139, 125, 242) // testo/link secondari, evidenziazioni
    val GOLD = Color.rgb(232, 190, 62)       // accento del quadrante, dettagli
    val CORAL = Color.rgb(255, 107, 92)      // azioni distruttive, errori
    val SUCCESS = Color.rgb(94, 196, 140)    // stato positivo (password sicure)
    val TEXT = Color.WHITE                   // testo principale su sfondo scuro
    val TEXT_DIM = Color.rgb(182, 177, 232)  // testo secondario su sfondo scuro
}
