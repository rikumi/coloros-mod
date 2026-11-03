package com.rikumi.colorosmod

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import com.rikumi.colorosmod.hooks.SystemFontHooks
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Build a concrete custom typeface, bypassing OEM replacement of named font families. */
internal object WifiBssidFont {
    fun load(context: Context): Typeface {
        val choice = context.settingsPrefs().getInt(SystemFontHooks.MONO, -1)
        val font = when (choice) {
            in 0..3 -> {
                val name = listOf("MapleMono", "JetBrainsMono", "FiraCode", "CascadiaCode")[choice]
                Font.Builder(context.assets, "system_fonts/$name.ttf").setWeight(400).build()
            }
            4 -> fromFile(File(context.createDeviceProtectedStorageContext().filesDir,
                "system_fonts/${SystemFontHooks.MONO}.font"))
            else -> systemDefault()
        }
        return Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build())
            .setStyle(font.style).build()
    }

    private fun fromFile(file: File): Font {
        val face = FontFileInfo.read(file).filter { it.monospace }
            .minByOrNull { FontFileInfo.matchScore(it, 400, false, "") }
            ?: error("No monospace face in $file")
        val builder = Font.Builder(file).setTtcIndex(face.index)
            .setWeight(face.weightAt(400)).setSlant(if (face.italic) 1 else 0)
        if (face.variable()) builder.setFontVariationSettings("'wght' ${face.weightAt(400)}")
        return builder.build()
    }

    private fun systemDefault(): Font {
        val candidates = linkedSetOf<File>()
        // Read the firmware's family definition, not the mutable Typeface family map.
        for (config in listOf("/system/etc/fonts.xml", "/system/etc/font_fallback.xml")) {
            runCatching {
                val factory = DocumentBuilderFactory.newInstance()
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                val families = factory.newDocumentBuilder().parse(File(config)).getElementsByTagName("family")
                for (i in 0 until families.length) {
                    val family = families.item(i) as org.w3c.dom.Element
                    if (family.getAttribute("name") != "monospace") continue
                    val fonts = family.getElementsByTagName("font")
                    for (j in 0 until fonts.length) {
                        val name = fonts.item(j).textContent.trim()
                        if (name.isNotEmpty()) candidates += File("/system/fonts", name)
                    }
                }
            }
        }
        // Standard Android firmware fallback if the configuration layout differs.
        candidates += File("/system/fonts/DroidSansMono.ttf")
        candidates += File("/system/fonts/RobotoMono-Regular.ttf")
        candidates += File("/system/fonts/NotoSansMono-Regular.ttf")
        for (file in candidates) if (file.isFile) runCatching { return fromFile(file) }
        error("Firmware monospace font unavailable")
    }
}
