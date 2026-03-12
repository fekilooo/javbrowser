# JAV Browser

## Latest Update Release Notes

### What's New in this Version:
1. **Dynamic Domain Configuration**:
   - The application now supports dynamic domain updates for blocked websites (e.g., `missav.ws`, `jable.tv`, `rou.video`).
   - By updating the cloud-hosted `ad-filter-rules.json` on GitHub, the application will automatically fetch and apply the latest accessible domains on startup.
   - You no longer need to recompile and reinstall the APK when a domain is blocked by DNS pollution!

2. **Smart Favorites (Bookmarks) Migration**:
   - Old bookmarks saved with blocked domains will now automatically redirect and seamlessly resolve to the latest available domain when clicked.
   - Fixed the "Heart" icon toggle logic to intelligently recognize videos across different domain aliases.

3. **Favorites Backup & Restore Feature**:
   - Added an **Export** (📥) and **Import** (📤) feature in the Favorites screen.
   - You can now safely backup your entire favorites collection as a `.json` file to your device's local storage (e.g., Downloads folder) using the native Android Storage Access Framework.
   - Easily restore your favorites if you switch devices or reinstall the app. The import process will automatically merge and prevent duplicate entries.

4. **Bug Fixes**:
   - Resolved UI corruption and Unicode replacement issues caused by terminal encoding during text manipulation.
   - Ensured `AdFilterRules` initializes properly on application start.

### How to Install
1. Download the latest `app-debug.apk` from the `release` folder in this repository.
2. Transfer the APK to your Android device.
3. Open the file manager and install the APK (you may need to allow installation from unknown sources).

### How to Update Domains (For Repository Owner)
If any supported website gets blocked, simply update the `domains` object in `ad-filter-rules.json` located in the root of the GitHub repository:
```json
"domains": {
    "missav": "new-missav-domain.com",
    "jable": "new-jable-domain.tv",
    "rou_video": "new-rou-domain.xyz"
}
```
The app will fetch the update the next time it is cold-started.

---
*Built with Kotlin and Android Studio.*
