package nz.co.ardp.connection

import com.freerdp.freerdpcore.domain.BookmarkBase
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ConnectionConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val hostname: String = "",
    val port: Int = 3389,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
) {
    fun toBookmark(): BookmarkBase {
        val bookmark = BookmarkBase()
        bookmark.label = name.ifBlank { hostname }
        bookmark.hostname = hostname
        bookmark.port = port
        bookmark.username = username
        bookmark.password = password
        bookmark.domain = domain

        val screen = bookmark.screenSettings
        screen.setResolution(BookmarkBase.ScreenSettings.AUTOMATIC)
        bookmark.screenSettings = screen

        return bookmark
    }
}
