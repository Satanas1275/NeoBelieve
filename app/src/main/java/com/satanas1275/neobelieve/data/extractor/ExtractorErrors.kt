package com.satanas1275.neobelieve.data.extractor

import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.net.UnknownHostException

/**
 * Traduit une exception d'extraction en message directement affichable à l'utilisateur,
 * pour éviter le "erreur inconnue" muet et pouvoir diagnostiquer sans logcat.
 */
fun Throwable.describeExtractionError(): String = when (this) {
    is ReCaptchaException ->
        "YouTube demande une vérification anti-bot (captcha) — réessaie plus tard"
    is ContentNotAvailableException ->
        "Contenu indisponible (supprimé, privé, ou bloqué dans ta région)"
    is ParsingException ->
        "YouTube a changé sa page, l'extracteur ne sait plus la lire (${message ?: "détail inconnu"})"
    is UnknownHostException ->
        "Pas de connexion internet"
    is IOException ->
        "Erreur réseau (${message ?: this::class.simpleName})"
    else ->
        message?.takeIf { it.isNotBlank() } ?: (this::class.simpleName ?: "erreur inconnue")
}
