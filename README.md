<div align="center">
  <img src="logo.svg" alt="Gallery by SK Logo" width="128">
  <h1>Gallery by SK</h1>
  <p>A simple, clean gallery app that preserves your folder structure and original timestamps, ensuring your photo organization stays perfectly intact even when you switch phones.</p>

  <a href="https://github.com/Naim120/gallery-by-sk/releases/">
    <img src="https://img.shields.io/github/v/release/Naim120/gallery-by-sk?label=Download%20APK&style=for-the-badge&color=success" alt="Download APK">
  </a>
</div>

<br/>

## The Problem

When switching to a new phone, most cloud backups (like Google Photos) lump all your media together. They strip away your custom folders, locally created albums, and original timestamps. Your carefully organized gallery becomes a messy, single timeline.

## The Solution

**Gallery by SK** is designed to feel familiar while solving this exact problem. When you export your media to the cloud and import it to a new device, it restores everything exactly as it was: the same folder structure, the same albums, and the exact same timestamps.

## Key Features

- **Clean & Familiar UI**: A straightforward, easy-to-use gallery experience.
- **Theme Support**: Seamlessly adapts to your device's Light or Dark mode.
- **Flawless Cloud Backup (Google Drive)**: Export and Import your photos while maintaining original folders and timestamps.
- **Built-in Editor**: Crop, rotate, add text, draw, apply filters, and adjust brightness/contrast directly in the app.
- **Collage Maker**: Create beautiful photo collages directly from your gallery.
- **Private Safe**: A secure, isolated vault for your sensitive media.
  - Requires PIN or Biometric authentication.
  - Prevents screenshots and immediately locks when you leave the app.
  - Features lightning-fast V2 Streaming Encryption, instantly encrypting massive files without eating up device memory.
  - Uses ExoPlayer to securely stream encrypted vault videos in real-time with zero buffering or wait times.
  - Backs up securely to Google Drive using encryption. A 12-word recovery phrase is required to restore them on a new device.
- **Smart Storage**: Checks your Google Drive space before uploading so backups don't fail halfway.
- **Resume Support**: If your network drops, the app pauses and lets you resume exactly where you left off.
- **Cloud Sync Indicators**: Thumbnails automatically display a small cloud icon when a file is successfully backed up, giving you peace of mind at a glance. To optimize the app, regular gallery updates the UI every 10 files of upload, and private safe updates the UI every 5 files of upload.
- **Recently Deleted**: Recover accidentally deleted files within 30 days.

## App Sections

### 1. Photos
View all media on your device in a grid grouped by date. Select multiple files to share, delete, add to an album, or move to the Private Safe.

### 2. Albums
Manage your media folders. Create new albums or delete existing ones. Features a "Recently Deleted" folder to safely recover files for up to 30 days.

### 3. Cloud Integration
Connect to Google Drive to seamlessly backup or restore your files.
- **Export**: Uploads your gallery and Private Safe securely. If you cancel a regular gallery export, any partial uploads from current device are deleted from the cloud to save space.
- **Import**: Restores files back to your device exactly how they were.
- **Delete**: Clears your cloud backup (including Private Safe) for the current device without touching your local files.

## Required Permissions

- **Manage All Files**: Needed to permanently delete files and create folders(albums) across your storage.
- **Photos & Videos**: Needed to read and display your media.
- **Notifications**: Used to display a progress bar during cloud backups and restores.

## Frequently Asked Questions

**1. Why does the app need the 'Manage all files' permission?**
To allow you to create and delete albums, and to permanently delete images and videos from your device.

**2. What happens when I export and import?**
Exporting safely uploads your files to Google Drive one by one, preserving your folders and timestamps. Importing downloads them back, automatically restoring your original folder structure and timestamps.

**3. I forgot my 12-word phrase for the Private Safe. What should I do?**
Your Private Safe files are securely encrypted in the cloud. Without the 12-word phrase, they cannot be decrypted or recovered. Keep this phrase stored somewhere very safe!

**4. What if my network drops in between an upload or import?**
If a network error occurs (like connection lost or timeout), the app automatically detects it, pauses the progress, and safely freezes the state. You can safely resume once your connection is restored.

**5. What happens if I cancel an export in the Regular Gallery?**
It will immediately stop the upload. To save cloud space, any files already uploaded from the current device will be deleted from your Google Drive. It will NOT delete any local files from your phone, nor will it delete Private Safe backups.

**6. What happens if I cancel an export in the Private Safe?**
It will immediately stop the upload. To save cloud space, any Private Safe files already uploaded from the current device will be deleted from your Google Drive. It will NOT delete your local Private Safe files or any Regular Gallery cloud backups.

**7. Does the app back up in the background?**
- **Regular Gallery:** Yes. It runs as a persistent "Foreground Service". Even if you swipe the app away, the backup will continue until it finishes.
- **Private Safe:** No. To maximize stealth and privacy, Private Safe backups will stop if you forcefully close the app. The app will automatically attempt to resume the next time you open it.

**8. What happens if I cancel an import?**
It simply stops the operation. No data is deleted from your phone or your cloud, for both the Regular Gallery and Private Safe.

**9. What happens when I delete an album?**
Deleting an album permanently deletes the data inside it from your device.

**10. What happens if I delete data using the Cloud Delete tab?**
It permanently removes the backup for the selected device from Google Drive and removes the cloud icons from your local gallery. Your local files on your phone are not affected.

## Roadmap (Coming Soon)
- Support for more cloud services (AWS S3, Wasabi, Cloudflare R2).
- Photo to PDF converter.
- Advanced editing tools (Blur, Sharpness).
- Custom themes and background colors.

## For Developers: Local Setup

To run and build Gallery by SK locally, you need to configure a few local secrets (like your Google API Client ID). These are deliberately kept out of version control for security.

1. Clone the repository.
2. In the root of the project, create a file named `secrets.properties`. (You can copy the provided `secrets.properties.example` file and rename it).
3. Fill in the required values:

```properties
# Required for Google Drive Cloud Backup.
# You can get this by creating an Android OAuth client in the Google Cloud Console.
OAUTH_CLIENT_ID=YOUR_GOOGLE_CLOUD_OAUTH_CLIENT_ID

# Required for building a Release APK.
# You can generate a keystore using Android Studio or the keytool CLI.
KEYSTORE_FILE=../release.keystore
KEYSTORE_PASSWORD=YOUR_KEYSTORE_PASSWORD
KEY_ALIAS=YOUR_KEY_ALIAS
KEY_PASSWORD=YOUR_KEY_PASSWORD
```

*Note: The `secrets.properties` file is included in `.gitignore` and will never be uploaded to GitHub.*

## License

This project is open-source and free to use for personal, academic, and educational purposes. **Commercial use is strictly prohibited.** You may not use, modify, or distribute this software to generate revenue, directly or indirectly. For full details, please see the [LICENSE](LICENSE) file.
