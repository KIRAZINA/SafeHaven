# Changelog

All notable changes to this project will be documented in this file.

## [1.0] - 2026-04-08

### Added
- **Initial Release** of SafeHaven secure file storage application
- Core encryption features:
  - AES-256-GCM symmetric encryption for files
  - PBKDF2 password-based key derivation (65,536 iterations)
  - RSA-2048 hybrid encryption for secure file sharing
- User authentication system with secure password hashing
- File management capabilities:
  - Upload and download encrypted files
  - Automatic GZIP compression before encryption
  - File search and filtering
  - Drag & drop file uploads
- Secure file sharing system using RSA key exchange
- Modern JavaFX user interface with dark theme
- Async operations with progress indicators
- Comprehensive unit test suite (28 tests)
- H2 embedded database for metadata and key storage
- FXML-based UI with CSS styling
- Properties-based configuration system

### Security Features
- Secure password handling (arrays cleared after use)
- SQL injection prevention with parameterized queries
- Secure random number generation
- Unique salt per user (16 bytes)
- Master key derivation with strong KDF
- Private key encryption with master key

### Technical Stack
- Java 17+
- JavaFX 17.0.2
- Maven 3+ build system
- H2 Database 2.1.214
- JUnit 5.9.2 with Mockito
- SLF4J logging

### Testing
- CryptoUtils cryptography tests
- AuthService authentication tests
- ShareService file sharing tests
- StorageService storage operations tests
- **Test Success Rate:** 100% (28/28 tests passing)

### Documentation
- Comprehensive README with features and usage
- Security architecture documentation
- Installation and setup instructions
- API and internal documentation

---

## Legend

- **Added** - New features
- **Changed** - Changes in existing functionality
- **Deprecated** - Soon to be removed features
- **Removed** - Now removed features
- **Fixed** - Bug fixes
- **Security** - Security fixes and improvements
