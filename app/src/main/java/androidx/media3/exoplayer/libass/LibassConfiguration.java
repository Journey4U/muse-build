package androidx.media3.exoplayer.libass;

// Compile-only stub for FongMi's private libass fork. Not functional at runtime.
public final class LibassConfiguration {

    private LibassConfiguration() {}

    public static final class Builder {

        public Builder setFontConfig(String fontConfigPath) {
            return this;
        }

        public Builder setFontsDirectory(String fontsDirectory) {
            return this;
        }

        public Builder setDefaultFontFamily(String fontFamily) {
            return this;
        }

        public Builder setMaximumRenderPixels(int maxRenderPixels) {
            return this;
        }

        public Builder setMaximumGlyphCount(int maxGlyphCount) {
            return this;
        }

        public Builder setMaximumBitmapCacheSizeMb(int maxBitmapCacheSizeMb) {
            return this;
        }

        public LibassConfiguration build() {
            return new LibassConfiguration();
        }
    }
}
