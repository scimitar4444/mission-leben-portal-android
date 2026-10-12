package de.missionleben.portal.web

import androidx.core.content.FileProvider
import de.missionleben.portal.R

/** A distinct Android component: the update provider must not shadow the photo provider. */
class TicketPhotoProvider : FileProvider(R.xml.ticket_photo_paths)
