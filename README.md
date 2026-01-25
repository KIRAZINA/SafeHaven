# SafeHaven - Secure File Storage

**SafeHaven** is a secure, local desktop application for storing files with strong encryption. Built with Java and JavaFX, it ensures your sensitive data remains private and accessible only to you.

## 🚀 Features

*   **🔒 Strong Encryption:** Files are encrypted using **AES-256 GCM** (Galois/Counter Mode) for confidentiality and integrity.
*   **🔑 Secure Authentication:** User passwords are hashed using **PBKDF2** with a unique salt per user.
*   **📦 Compression:** Files are compressed with **GZIP** before encryption to save space.
*   **🌗 Modern UI:** Clean, responsive interface with a **Dark Theme**.
*   **⚡ Async Operations:** Uploads and downloads run in the background with progress indicators.
*   **🔎 Search:** Quickly filter your stored files by name.
*   **📂 Drag & Drop:** Easily upload files by dragging them into the application.

## 🛠️ Technology Stack

*   **Language:** Java 17+
*   **UI Framework:** JavaFX
*   **Build Tool:** Maven
*   **Database:** H2 Database (Embedded)
*   **Cryptography:** Java Cryptography Architecture (JCA)

## 📥 Installation & Run

### Prerequisites
*   Java Development Kit (JDK) 17 or higher.
*   Maven.

### Running with Maven
```bash
git clone https://github.com/yourusername/SafeHaven.git
cd SafeHaven
mvn javafx:run
```

### Running the JAR
You can also build a self-contained JAR file:
```bash
mvn clean package
java -jar target/SafeHaven-1.0-SNAPSHOT.jar
```

## 🛡️ Security Details

1.  **Key Derivation:** When you register, a random **16-byte Salt** is generated. Your password + salt are processed via **PBKDF2WithHmacSHA256** (65,536 iterations) to derive your master encryption key.
2.  **File Encryption:** Each file is encrypted with a unique **12-byte IV** (Initialization Vector) using AES-256 GCM. The IV is stored alongside the file metadata.
3.  **Local Storage:** Encrypted files are stored in your home directory under `~/.securestorage`. Metadata is stored in a local H2 database file.

## ⚠️ Disclaimer
SafeHaven is a demonstration of secure application principles. While it uses industry-standard algorithms, always ensure you have backups of your critical data.
