# SafeHaven v1.0 - Release Notes

**Release Date:** April 8, 2026

## 🎉 Initial Release

SafeHaven v1.0 is the first stable release of the secure file storage application. This release includes all core features for secure local file storage with strong encryption and secure sharing capabilities.

## ✨ Features

### Security
- **AES-256-GCM Encryption:** Military-grade symmetric encryption for all stored files
- **PBKDF2 Password Hashing:** 65,536 iterations with unique per-user salt
- **RSA-2048 Key Exchange:** Secure hybrid encryption for file sharing
- **Master Key Protection:** Private keys are encrypted with your master key derived from password

### File Management
- **Upload & Download:** Simple interface for managing encrypted files
- **Automatic Compression:** GZIP compression before encryption to save storage space
- **File Search:** Quick filtering to find files by name
- **Drag & Drop:** Easy file uploads directly into the application
- **Async Operations:** Background uploads/downloads with progress indicators

### Sharing
- **Secure Sharing:** Share encrypted files with other users without exposing your master key
- **Tabs for Shared Files:** Dedicated view for files shared with you
- **RSA-Based Wrapping:** File encryption keys are wrapped using recipient's public key

### User Interface
- **Modern Dark Theme:** Responsive JavaFX interface with clean design
- **Tab-based Navigation:** Easy switching between your files and shared files
- **Progress Indicators:** Real-time feedback during encryption/decryption operations

## 🛡️ Security Assurance

✅ **Code Review:** All security-critical components reviewed
✅ **Unit Tests:** 28 comprehensive tests covering cryptography, authentication, and file operations
✅ **Secure Coding Practices:** 
   - Password arrays cleared from memory after use
   - Parameterized SQL queries to prevent injection
   - Proper exception handling without exposing sensitive information

## 🔧 Technical Details

| Component | Technology |
|-----------|-----------|
| Language | Java 17+ |
| UI Framework | JavaFX 17.0.2 |
| Build Tool | Maven 3+ |
| Database | H2 2.1.214 (Embedded) |
| Cryptography | Java Cryptography Architecture (JCA) |
| Compression | GZIP (Java built-in) |

## 📦 Build Artifacts

Two JAR files are included in the release:

1. **SafeHaven-1.0.jar** - Standalone executable JAR (recommended)
   - Contains all dependencies shaded
   - Ready to run: `java -jar SafeHaven-1.0.jar`

2. **original-SafeHaven-1.0.jar** - Original compiled classes
   - Requires all dependencies in classpath

## 🚀 Getting Started

### Prerequisites
- Java 17 or higher installed
- 100 MB free disk space for database and file storage

### Installation
```bash
# Download SafeHaven-1.0.jar

# Run the application
java -jar SafeHaven-1.0.jar
```

### First Time Setup
1. Click "Register" to create your account
   - Choose a strong password (will be hashed with PBKDF2)
   - Your RSA-2048 key pair is automatically generated
2. Log in with your credentials
3. Start uploading files by dragging them into the app

## 🔍 Known Information

- **Storage Location:** Encrypted files are stored in `~/.securestorage` directory
- **Database:** H2 database stored in current directory as `safehaven.mv.db`
- **Default Database URL:** `jdbc:h2:./safehaven;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE`

## ⚠️ Disclaimer

SafeHaven is provided on an "as-is" basis. While this application implements robust encryption standards, no cipher is absolutely unbreakable. The security of your data ultimately depends on:
- The strength of your password
- Your computer's security
- Regular software updates

For sensitive data, consider using this as part of a comprehensive security strategy.

## 📝 Version Information

- **Version:** 1.0
- **Build Date:** April 8, 2026
- **Status:** Stable Release
- **Java Compatibility:** 17+

## 🔄 Future Updates

Planned improvements for future versions:
- Support for cloud backup integration
- Enhanced sharing invitations
- User activity logging
- Performance optimizations for large files
- Cross-platform packaging (Windows/macOS/Linux installers)

## 📞 Support

For issues, questions, or suggestions, please open an issue on the GitHub repository.

---

**Thank you for using SafeHaven!** 🔒
