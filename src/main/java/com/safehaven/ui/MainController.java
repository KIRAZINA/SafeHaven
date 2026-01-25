package com.safehaven.ui;

import com.safehaven.MainApp;
import com.safehaven.crypto.CryptoUtils;
import com.safehaven.model.FileMetadata;
import com.safehaven.model.User;
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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Optional;

public class MainController {

    @FXML
    private TableView<FileMetadata> fileTable;
    @FXML
    private TableColumn<FileMetadata, String> filenameColumn;
    @FXML
    private TableColumn<FileMetadata, Number> sizeColumn;
    @FXML
    private TableColumn<FileMetadata, String> dateColumn;
    @FXML
    private Label welcomeLabel;
    @FXML
    private TextField searchField;
    @FXML
    private ProgressBar progressBar;

    private User currentUser;
    private SecretKey secretKey;
    private final StorageService storageService = new StorageService();
    private final ObservableList<FileMetadata> masterData = FXCollections.observableArrayList();
    private FilteredList<FileMetadata> filteredData;

    public void initData(User user, char[] password) {
        this.currentUser = user;
        this.secretKey = CryptoUtils.generateKeyFromPassword(password, user.getSalt());
        
        welcomeLabel.setText("Welcome, " + user.getUsername());
        
        setupTable();
        setupSearch();
        refreshFileList();
        setupDragAndDrop();
    }

    private void setupTable() {
        filenameColumn.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getFilename()));
        sizeColumn.setCellValueFactory(cellData -> new SimpleLongProperty(cellData.getValue().getOriginalSize()));
        dateColumn.setCellValueFactory(cellData -> {
            Date date = new Date(cellData.getValue().getTimestamp());
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return new SimpleStringProperty(sdf.format(date));
        });
        
        filteredData = new FilteredList<>(masterData, p -> true);
        fileTable.setItems(filteredData);
    }
    
    private void setupSearch() {
        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            filteredData.setPredicate(file -> {
                if (newValue == null || newValue.isEmpty()) {
                    return true;
                }
                String lowerCaseFilter = newValue.toLowerCase();
                return file.getFilename().toLowerCase().contains(lowerCaseFilter);
            });
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
        // Run on background thread? Not strictly necessary for simple DB select but good practice.
        // For now, keep simple on UI thread as it's fast H2 select.
        List<FileMetadata> files = storageService.listFiles(currentUser);
        masterData.setAll(files);
    }

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
                // Determine step size roughly? 
                // StorageService doesn't report progress yet.
                // We'll set indeterminate for now.
                updateProgress(-1, 1); 
                storageService.uploadFile(file, currentUser, secretKey);
                return null;
            }
        };

        bindProgress(task);
        
        task.setOnSucceeded(e -> {
            unbindProgress();
            refreshFileList();
            showAlert("Success", "File uploaded successfully.");
        });

        task.setOnFailed(e -> {
            unbindProgress();
            Throwable ex = task.getException();
            ex.printStackTrace();
            showAlert("Upload Failed", "Could not upload file: " + ex.getMessage());
        });

        new Thread(task).start();
    }

    @FXML
    private void handleDownload() {
        FileMetadata selected = fileTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Save File");
            fileChooser.setInitialFileName(selected.getFilename());
            File dest = fileChooser.showSaveDialog(fileTable.getScene().getWindow());
            if (dest != null) {
                downloadFile(selected, dest);
            }
        } else {
            showAlert("Warning", "Please select a file to download.");
        }
    }
    
    private void downloadFile(FileMetadata meta, File dest) {
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
            showAlert("Success", "File downloaded successfully.");
        });

        task.setOnFailed(e -> {
            unbindProgress();
            Throwable ex = task.getException();
            ex.printStackTrace();
            showAlert("Download Failed", "Could not download file: " + ex.getMessage());
        });

        new Thread(task).start();
    }

    @FXML
    private void handleDelete() {
        FileMetadata selected = fileTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Confirm Delete");
            alert.setHeaderText("Are you sure you want to delete this file?");
            alert.setContentText(selected.getFilename());

            Optional<ButtonType> result = alert.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                deleteFile(selected);
            }
        } else {
            showAlert("Warning", "Please select a file to delete.");
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
            showAlert("Delete Failed", "Could not delete file: " + task.getException().getMessage());
        });

        new Thread(task).start();
    }
    
    private void bindProgress(Task<?> task) {
        progressBar.setVisible(true);
        progressBar.progressProperty().bind(task.progressProperty());
        fileTable.setDisable(true); // Disable table during operation
    }
    
    private void unbindProgress() {
        progressBar.setVisible(false);
        progressBar.progressProperty().unbind();
        progressBar.setProgress(0);
        fileTable.setDisable(false);
    }

    @FXML
    private void handleLogout() throws Exception {
        this.secretKey = null;
        this.currentUser = null;
        MainApp.setRoot("ui/login");
    }

    private void showAlert(String title, String content) {
        // Alerts must be on FX thread
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }
}
