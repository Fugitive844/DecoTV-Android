package io.github.fugitive844.decotv;

import android.app.Application;

import io.github.fugitive844.decotv.data.Prefs;
import io.github.fugitive844.decotv.download.DownloadStore;
import io.github.fugitive844.decotv.net.ApiClient;
import io.github.fugitive844.decotv.net.ImageLoader;

public class DecoApp extends Application {
    private static DecoApp instance;
    public Prefs prefs;
    public ApiClient api;
    public ImageLoader images;
    public DownloadStore downloads;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        io.github.fugitive844.decotv.ui.CrashReport.install(this);
        prefs = new Prefs(this);
        prefs.applyOneTimeMigrations();
        prefs.applyThemeMode();
        api = new ApiClient(prefs);
        images = new ImageLoader(api, prefs);
        downloads = new DownloadStore(this);
    }

    public static DecoApp get() { return instance; }
}
