package com.safehaven.ui;

import com.safehaven.MainApp;
import com.safehaven.crypto.CryptoUtils;
import com.safehaven.model.FileMetadata;
import com.safehaven.model.SharedFileEntry;
import com.safehaven.model.User;
import com.safehaven.service.ShareService;
import com.safehaven.service.StorageService;
import javafx.application.Platform;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.stage.FileChooser;

import javax.crypto.SecretKey;
import java.io.File;
import java.security.PrivateKey;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainController {

    // General UI
    @FXML private Label welcomeLabel;
    @FXML private TextField searchField;
    @FXML private ProgressBar progressBar;
    @FXML private TabPane tabPane;

    // My Files Tab
    @FXML private TableView<FileMetadata> fileTable;
    @FXML private TableColumn<FileMetadata, String> filenameColumn;
    @FXML private TableColumn<FileMetadata, Number> sizeColumn;
    @FXML private TableColumn<FileMetadata, String> dateColumn;

    // Shared With Me Tab
    @FXML private TableView<SharedFileEntry> sharedTable;
    @FXML private TableColumn<SharedFileEntry, String> sharedFilenameColumn;
    @FXML private TableColumn<SharedFileEntry, String> sharedFromColumn;
    @FXML private TableColumn<SharedFileEntry, Number> sharedSizeColumn;
    @FXML private TableColumn<SharedFileEntry, String> sharedDateColumn;

    private User currentUser;
    private SecretKey secretKey;
    private PrivateKey privateKey; // Added: RSVP Private Key to unwrap shared FEKs

    private final StorageService storageService = new StorageService();
    private final ShareService shareService = new ShareService();

    private final ObservableList<FileMetadata> myFilesData = FXCollections.observableArrayList();
    private FilteredList<FileMetadata> filteredMyFilesData;

    private final ObservableList<SharedFileEntry> sharedFilesData = FXCollections.observableArrayList();
    private FilteredList<SharedFileEntry> filteredSharedFilesData;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "safehaven-io");
        t.setDaemon(true);
        return t;
    });

    public void initData(User user, char[] password) {
        this.currentUser = user;
        // Derive master AES key
        this.secretKey = CryptoUtils.generateKeyFromPassword(password, user.getSalt());
        
        // Decrypt the user's RSA private key
        if (user.getEncryptedPrivateKey() != null) {
            this.privateKey = CryptoUtils.decryptPrivateKey(user.getEncryptedPrivateKey(), this.secretKey);
        }

        Arrays.fill(password, '\0');

        welcomeLabel.setText("Welcome, " + user.getUsername());

        setupTables();
        setupSearch();
        refreshFileList();
        refreshSharedFileList();
        setupDragAndDrop();
    }

    private void setupTables() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        // My Files Table
        filenameColumn.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getFilename()));
        sizeColumn.setCellValueFactory(cd -> new SimpleLongProperty(cd.getValue().getOriginalSize()));
        dateColumn.setCellValueFactory(cd -> new SimpleStringProperty(sdf.format(new Date(cd.getValue().getTimestamp()))));

        filteredMyFilesData = new FilteredList<>(myFilesData, p -> true);
        fileTable.setItems(filteredMyFilesData);

        // Shared Table
        sharedFilenameColumn.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getFilename()));
        sharedFromColumn.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getFromUser()));
        sharedSizeColumn.setCellValueFactory(cd -> new SimpleLongProperty(cd.getValue().getOriginalSize()));
        sharedDateColumn.setCellValueFactory(cd -> new SimpleStringProperty(sdf.format(new Date(cd.getValue().getTimestamp()))));

        filteredSharedFilesData = new FilteredList<>(sharedFilesData, p -> true);
        sharedTable.setItems(filteredSharedFilesData);
    }

    private void setupSearch() {
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            String lowerCaseFilter = (newVal == null) ? "" : newVal.toLowerCase();
            
            filteredMyFilesData.setPredicate(file -> 
                lowerCaseFilter.isEmpty() || file.getFilename().toLowerCase().contains(lowerCaseFilter)
            );
            
            filteredSharedFilesData.setPredicate(entry -> 
                lowerCaseFilter.isEmpty() || 
                entry.getFilename().toLowerCase().contains(lowerCaseFilter) ||
                entry.getFromUser().toLowerCase().contains(lowerCaseFilter)
            );
        });
    }

    private void setupDragAndDrop() {
        fileTable.setOnDragOver(event -> {
            if (event.getGestureSource() != fileTable && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
            }
            event.consume();
        });

        fileTable.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                for (File file : db.getFiles()) {
                    uploadFile(file);
                }
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    private void refreshFileList() {
        List<FileMetadata> files = storageService.listFiles(currentUser);
        Platform.runLater(() -> myFilesData.setAll(files));
    }

    private void refreshSharedFileList() {
        List<SharedFileEntry> shares = shareService.listSharedWithMe(currentUser);
        Platform.runLater(() -> sharedFilesData.setAll(shares));
    }

    // ─── My Files Actions ───────────────────────────────────────────────────

    @FXML
    private void handleUpload() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select File to Upload");
        File file = fileChooser.showOpenDialog(fileTable.getScene().getWindow());
        if (file != null) {
            uploadFile(file);
        }
    }

    private void uploadFile(File file) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                updateProgress(-1, 1);
                storageService.uploadFile(file, currentUser, secretKey);
                return null;
            }
        };
        bindProgress(task);
        task.setOnSucceeded(e -> {
            unbindProgress();
            refreshFileList();
            showAlert(Alert.AlertType.INFORMATION, "Done", "File uploaded successfully.");
        });
        task.setOnFailed(e -> {
            unbindProgress();
            Throwable ex = task.getException();
            showAlert(Alert.AlertType.ERROR, "Upload Error", "Failed to upload file: " + ex.getMessage());
        });
        executor.submit(task);
    }

    @FXML
    private void handleDownload() {
        FileMetadata selected = fileTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert(Alert.AlertType.WARNING, "Warning", "Please select a file to download.");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save File");
        fileChooser.setInitialFileName(selected.getFilename());
        File dest = fileChooser.showSaveDialog(fileTable.getScene().getWindow());
        if (dest != null) {
            downloadMyFile(selected, dest);
        }
    }

    private void downloadMyFile(FileMetadata meta, File dest) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                updateProgress(-1, 1);
                storageService.downloadFile(meta, currentUser, secretKey, dest);
                return null;
            }
        };
        bindProgress(task);
        task.setOnSucceeded(e -> {
            unbindProgress();
            showAlert(Alert.AlertType.INFORMATION, "Done", "File downloaded successfully.");
        });
        task.setOnFailed(e -> {
            unbindProgress();
            showAlert(Alert.AlertType.ERROR, "Download Error", "Failed to download file: " + task.getException().getMessage());
        });
        executor.submit(task);
    }

    @FXML
    private void handleDelete() {
        FileMetadata selected = fileTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert(Alert.AlertType.WARNING, "Warning", "Please select a file to delete.");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Delete");
        confirm.setHeaderText("Are you sure you want to delete this file?");
        confirm.setContentText(selected.getFilename());

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            deleteFile(selected);
        }
    }

    private void deleteFile(FileMetadata meta) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                updateProgress(-1, 1);
                storageService.deleteFile(meta, currentUser);
                return null;
            }
        };
        bindProgress(task);
        task.setOnSucceeded(e -> {
            unbindProgress();
            refreshFileList();
        });
        task.setOnFailed(e -> {
            unbindProgress();
            showAlert(Alert.AlertType.ERROR, "Delete Error", "Failed to delete file: " + task.getException().getMessage());
        });
        executor.submit(task);
    }

    @FXML
    private void handleShare() {
        FileMetadata selected = fileTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert(Alert.AlertType.WARNING, "Warning", "Please select a file to share.");
            return;
        }

        TextInputDialog dialog = new TextInputDialog("");
        dialog.setTitle("Share File");
        dialog.setHeaderText("Share with: " + selected.getFilename());
        dialog.setContentText("Username:");

        Optional<String> result = dialog.showAndWait();
        if (result.isPresent() && !result.get().trim().isEmpty()) {
            String recipient = result.get().trim();
            shareFile(selected, recipient);
        }
    }

    private void shareFile(FileMetadata meta, String recipient) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                updateProgress(-1, 1);
                shareService.shareFile(meta, recipient, currentUser, secretKey);
                return null;
            }
        };
        bindProgress(task);
        task.setOnSucceeded(e -> {
            unbindProgress();
            showAlert(Alert.AlertType.INFORMATION, "Success", "File successfully shared with " + recipient + ".");
        });
        task.setOnFailed(e -> {
            unbindProgress();
            showAlert(Alert.AlertType.ERROR, "Share Error", "Failed to share file: " + task.getException().getMessage());
        });
        executor.submit(task);
    }

    // ─── Shared With Me Actions ─────────────────────────────────────────────

    @FXML
    private void handleDownloadShared() {
        SharedFileEntry selected = sharedTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert(Alert.AlertType.WARNING, "Warning", "Please select a file to download.");
            return;
        }
        
        if (privateKey == null) {
            showAlert(Alert.AlertType.ERROR, "Error", "Your account does not have RSA keys. Download is not possible.");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Shared File");
        fileChooser.setInitialFileName(selected.getFilename());
        File dest = fileChooser.showSaveDialog(sharedTable.getScene().getWindow());
        if (dest != null) {
            downloadSharedFile(selected, dest);
        }
    }

    private void downloadSharedFile(SharedFileEntry entry, File dest) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                updateProgress(-1, 1);
                shareService.downloadSharedFile(entry, privateKey, dest);
                return null;
            }
        };
        bindProgress(task);
        task.setOnSucceeded(e -> {
            unbindProgress();
            showAlert(Alert.AlertType.INFORMATION, "Done", "Shared file downloaded successfully.");
        });
        task.setOnFailed(e -> {
            unbindProgress();
            showAlert(Alert.AlertType.ERROR, "Download Error", "Failed to download file: " + task.getException().getMessage());
        });
        executor.submit(task);
    }

    // ─── Utilities ──────────────────────────────────────────────────────────

    private void bindProgress(Task<?> task) {
        Platform.runLater(() -> {
            progressBar.setVisible(true);
            progressBar.progressProperty().bind(task.progressProperty());
            tabPane.setDisable(true); // Disable entire UI during operation
        });
    }

    private void unbindProgress() {
        Platform.runLater(() -> {
            progressBar.setVisible(false);
            progressBar.progressProperty().unbind();
            progressBar.setProgress(0);
            tabPane.setDisable(false);
        });
    }

    @FXML
    private void handleLogout() throws Exception {
        if (secretKey != null) {
            try { secretKey.destroy(); } catch (javax.security.auth.DestroyFailedException ignored) {}
        }
        if (privateKey != null) {
            try { privateKey.destroy(); } catch (javax.security.auth.DestroyFailedException ignored) {}
        }
        executor.shutdown();
        this.secretKey = null;
        this.privateKey = null;
        this.currentUser = null;
        MainApp.setRoot("ui/login");
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }
}
