package com.example.toyotaobd.car

import android.graphics.Bitmap
import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Pane
import androidx.car.app.model.Row
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import com.example.toyotaobd.R

/** Lignes qu'un panneau montre sur cette voiture, sans défilement. */
internal fun CarContext.paneSize(): Int =
    if (carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
        getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PANE)
    } else {
        DEFAULT_PANE_SIZE
    }

/**
 * Panneau d'une valeur du carrousel : ses lignes [rows] (dans la limite de l'écran, le même nombre
 * pour toutes les valeurs), l'image [image] à côté (API 4 de l'hôte) et les flèches précédente et
 * suivante, s'il y a plusieurs valeurs. Un panneau ne défile pas : rien ne bouge au rafraîchissement.
 */
internal fun CarContext.detailPane(rows: List<CarDetailRow>, image: Bitmap?, onPrevious: (() -> Unit)?, onNext: (() -> Unit)?): Pane {
    val pane = Pane.Builder()
    for (row in rows.take(minOf(CAR_DETAIL_TITLES.size, paneSize()))) {
        val builder = Row.Builder().setTitle(row.title)
        row.texts.forEach(builder::addText)
        pane.addRow(builder.build())
    }
    if (image != null && carAppApiLevel >= CarAppApiLevels.LEVEL_4) {
        pane.setImage(CarIcon.Builder(IconCompat.createWithBitmap(image)).build())
    }
    onPrevious?.let { pane.addAction(arrow(R.drawable.ic_car_previous, it)) }
    onNext?.let { pane.addAction(arrow(R.drawable.ic_car_next, it)) }
    return pane.build()
}

private fun CarContext.arrow(icon: Int, onClick: () -> Unit): Action =
    Action.Builder()
        .setIcon(CarIcon.Builder(IconCompat.createWithResource(this, icon)).build())
        .setOnClickListener(onClick)
        .build()

private const val DEFAULT_PANE_SIZE = 4
