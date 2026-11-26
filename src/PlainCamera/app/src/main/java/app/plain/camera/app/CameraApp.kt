package app.plain.camera.app

import android.app.Application
import app.plain.hub.Hub
import app.plain.hub.Tab
import app.plain.migrate.Migrate

/** Plain Camera: Camera (default) and Gallery tabs. */
class CameraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.setup(this, "Plain Camera", listOf(
            Tab("camera", "Camera", "Kamera", "app.plain.camera.CameraActivity"),
            Tab("gallery", "Gallery", "Galeri", "app.plain.gallery.GalleryActivity",
                import = Migrate.Spec("app.plain.gallery", "Plain Gallery",
                    "its categories and settings (your photos stay where they are)",
                    "kategorilerini ve ayarlarını (fotoğraflarınız yerinde kalır)")),
        ))
    }
}
