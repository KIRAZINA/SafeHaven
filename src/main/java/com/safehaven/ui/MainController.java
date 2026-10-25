package com.safehaven.ui;

import com.safehaven.MainApp;
import com.safehaven.crypto.CryptoUtils;
import com.safehaven.model.FileMetadata;
import com.safehaven.model.SharedFileEntry;
import com.safehaven.model.User;
import com.safehaven.service.IShareService;
import com.safehaven.service.IStorageService;
import com.safehaven.service.InactivityService;
import com.safehaven.service.ShareService;
import com.safehaven.service.StorageService;
import javafx.application.Platform;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import javax.security.auth.DestroyFailedException;
import java.io.File;
import java.security.PrivateKey;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.IntConsumer;

public class MainController {

    private static final Logger logger = LoggerFactory.getLogger(MainController.class);

    static final int DEFAULT_PAGE_SIZE = 25;
    static final List<Integer> PAGE_SIZE_OPTIONS = List.of(10, 25, 50, 100);

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
    @FXML private Button myFilesPrevButton;
    @FXML private Button myFilesNextButton;
    @FXML private Label myFilesPageLabel;
    @FXML private ComboBox<Integer> myFilesPageSizeBox;

    // Shared With Me Tab
    @FXML private TableView<SharedFileEntry> sharedTable;
    @FXML private TableColumn<SharedFileEntry, String> sharedFilenameColumn;
    @FXML private TableColumn<SharedFileEntry, String> sharedFromColumn;
    @FXML private TableColumn<SharedFileEntry, Number> sharedSizeColumn;
    @FXML private TableColumn<SharedFileEntry, String> sharedDateColumn;
    @FXML private Button sharedPrevButton;
    @FXML private Button sharedNextButton;
    @FXML private Label sharedPageLabel;
    @FXML private ComboBox<Integer> sharedPageSizeBox;

    private User currentUser;
    private SecretKey secretKey;
    private PrivateKey privateKey; // Added: RSVP Private Key to unwrap shared FEKs

    private InactivityService inactivityService;
    private Scene inactivityScene;

    private final EventHandler<MouseEvent> mouseActivityHandler = event -> recordActivity();
    private final EventHandler<KeyEvent> keyActivityHandler = event -> recordActivity();
    private final EventHandler<ScrollEvent> scrollActivityHandler = event -> recordActivity();

    private final ChangeListener<Scene> sceneChangeListener = (observable, oldScene, newScene) -> {
        if (newScene != null) {
            attachInactivityHandlers(newScene);
        }
    };

    private IStorageService storageService = new StorageService();
    private IShareService shareService = new ShareService();

    /** For dependency injection (tests). */
    public void setStorageService(IStorageService storageService) {
        this.storageService = storageService;
    }

    /** For dependency injection (tests). */
    public void setShareService(IShareService shareService) {
        this.shareService = shareService;
    }

    private final ObservableList<FileMetadata> myFilesData = FXCollections.observableArrayList();
    private final ObservableList<SharedFileEntry> sharedFilesData = FXCollections.observableArrayList();

    // Pagination state (pages are zero-based internally).
    private int myFilesPage = 0;
    private int myFilesPageSize = DEFAULT_PAGE_SIZE;
    private long myFilesTotalItems = 0;
    private int myFilesTotalPages = 0;

    private int sharedFilesPage = 0;
    private int sharedFilesPageSize = DEFAULT_PAGE_SIZE;
    private long sharedFilesTotalItems = 0;
    private int sharedFilesTotalPages = 0;

    // Guards against slow, out-of-order results overwriting a newer query.
    private long myFilesLoadGeneration = 0;
    private long sharedFilesLoadGeneration = 0;

    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "safehaven-io");
        t.setDaemon(true);
        return t;
    });

    public void initData(User user, char[] password) {
        try {
            this.currentUser = user;

            // Derive master AES key
            this.secretKey = CryptoUtils.deriveMasterKey(password, user.getSalt());

            // Decrypt the user's RSA private key
            if (user.getEncryptedPrivateKey() != null) {
                this.privateKey = CryptoUtils.decryptPrivateKey(
                        user.getEncryptedPrivateKey(), this.secretKey);
            }

            welcomeLabel.setText("Welcome, " + user.getUsername());

            setupTables();
            setupPagination();
            setupSearch();
            refreshFileList();
            refreshSharedFileList();
            setupDragAndDrop();

            startInactivityMonitoring();
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    private void setupTables() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        // My Files Table
        filenameColumn.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getFilename()));
        sizeColumn.setCellValueFactory(cd -> new SimpleLongProperty(cd.getValue().getOriginalSize()));
        dateColumn.setCellValueFactory(cd -> new SimpleStringProperty(sdf.format(new Date(cd.getValue().getTimestamp()))));

        fileTable.setItems(myFilesData);

        // Shared Table
        sharedFilenameColumn.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getFilename()));
        sharedFromColumn.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getFromUser()));
        sharedSizeColumn.setCellValueFactory(cd -> new SimpleLongProperty(cd.getValue().getOriginalSize()));
        sharedDateColumn.setCellValueFactory(cd -> new SimpleStringProperty(sdf.format(new Date(cd.getValue().getTimestamp()))));

        sharedTable.setItems(sharedFilesData);
    }

    private void setupPagination() {
        configurePageSizeBox(myFilesPageSizeBox, myFilesPageSize, size -> {
            myFilesPageSize = size;
            myFilesPage = 0;
            refreshFileList();
        });
        configurePageSizeBox(sharedPageSizeBox, sharedFilesPageSize, size -> {
            sharedFilesPageSize = size;
            sharedFilesPage = 0;
            refreshSharedFileList();
        });

        updateMyFilesPaginationControls();
        updateSharedFilesPaginationControls();
    }

    private static void configurePageSizeBox(ComboBox<Integer> box, int initialSize,
                                             IntConsumer onSizeChanged) {
        box.setItems(FXCollections.observableArrayList(PAGE_SIZE_OPTIONS));
        box.setValue(initialSize);
        box.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue == null || newValue <= 0 || newValue.equals(oldValue)) {
                return;
            }
            onSizeChanged.accept(newValue);
        });
    }

    private void setupSearch() {
        // Search is executed by SQL, so it spans every row rather than only
        // filtering the rows already loaded for the current page.
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            myFilesPage = 0;
            sharedFilesPage = 0;
            refreshFileList();
            refreshSharedFileList();
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
        final User user = currentUser;
        if (user == null) {
            return;
        }

        final long generation = ++myFilesLoadGeneration;
        final int requestedPage = myFilesPage;
        final int requestedSize = myFilesPageSize;
        final String search = currentSearch();

        setPaginationBusy(myFilesPrevButton, myFilesNextButton);

        Task<PageResult<FileMetadata>> task = new Task<>() {
            @Override
            protected PageResult<FileMetadata> call() throws Exception {
                long totalItems = storageService.getFileCount(user, search);
                int page = clampPage(requestedPage, totalItems, requestedSize);
                int offset = pageOffset(page, requestedSize);
                List<FileMetadata> items = totalItems == 0
                        ? List.of()
                        : storageService.listFilesPaginated(user, search, requestedSize, offset);
                return new PageResult<>(items, totalItems, page);
            }
        };

        task.setOnSucceeded(e -> {
            if (generation != myFilesLoadGeneration) {
                return; // A newer refresh superseded this result.
            }
            PageResult<FileMetadata> result = task.getValue();
            myFilesPage = result.page();
            myFilesTotalItems = result.totalItems();
            myFilesTotalPages = totalPages(myFilesTotalItems, myFilesPageSize);
            myFilesData.setAll(result.items());
            updateMyFilesPaginationControls();
        });

        task.setOnFailed(e -> {
            if (generation != myFilesLoadGeneration) {
                return;
            }
            logger.error("Failed to load page {} of files for {}",
                    requestedPage, user.getUsername(), task.getException());
            updateMyFilesPaginationControls();
            showAlert(Alert.AlertType.ERROR, "Load Error",
                    "Failed to load files: " + messageOf(task.getException()));
        });

        submit(task);
    }

    private void refreshSharedFileList() {
        final User user = currentUser;
        if (user == null) {
            return;
        }

        final long generation = ++sharedFilesLoadGeneration;
        final int requestedPage = sharedFilesPage;
        final int requestedSize = sharedFilesPageSize;
        final String search = currentSearch();

        setPaginationBusy(sharedPrevButton, sharedNextButton);

        Task<PageResult<SharedFileEntry>> task = new Task<>() {
            @Override
            protected PageResult<SharedFileEntry> call() throws Exception {
                long totalItems = shareService.getSharedFileCount(user, search);
                int page = clampPage(requestedPage, totalItems, requestedSize);
                int offset = pageOffset(page, requestedSize);
                List<SharedFileEntry> items = totalItems == 0
                        ? List.of()
                        : shareService.listSharedWithMePaginated(user, search, requestedSize, offset);
                return new PageResult<>(items, totalItems, page);
            }
        };

        task.setOnSucceeded(e -> {
            if (generation != sharedFilesLoadGeneration) {
                return;
            }
            PageResult<SharedFileEntry> result = task.getValue();
            sharedFilesPage = result.page();
            sharedFilesTotalItems = result.totalItems();
            sharedFilesTotalPages = totalPages(sharedFilesTotalItems, sharedFilesPageSize);
            sharedFilesData.setAll(result.items());
            updateSharedFilesPaginationControls();
        });

        task.setOnFailed(e -> {
            if (generation != sharedFilesLoadGeneration) {
                return;
            }
            logger.error("Failed to load page {} of shared files for {}",
                    requestedPage, user.getUsername(), task.getException());
            updateSharedFilesPaginationControls();
            showAlert(Alert.AlertType.ERROR, "Load Error",
                    "Failed to load shared files: " + messageOf(task.getException()));
        });

        submit(task);
    }

    // ─── Pagination ─────────────────────────────────────────────────────────

    @FXML
    private void handleMyFilesPreviousPage() {
        if (myFilesPage > 0) {
            myFilesPage--;
            refreshFileList();
        }
    }

    @FXML
    private void handleMyFilesNextPage() {
        if (myFilesPage + 1 < myFilesTotalPages) {
            myFilesPage++;
            refreshFileList();
        }
    }

    @FXML
    private void handleSharedPreviousPage() {
        if (sharedFilesPage > 0) {
            sharedFilesPage--;
            refreshSharedFileList();
        }
    }

    @FXML
    private void handleSharedNextPage() {
        if (sharedFilesPage + 1 < sharedFilesTotalPages) {
            sharedFilesPage++;
            refreshSharedFileList();
        }
    }

    private void updateMyFilesPaginationControls() {
        updatePaginationControls(myFilesPageLabel, myFilesPrevButton, myFilesNextButton,
                myFilesPage, myFilesTotalPages, myFilesTotalItems);
    }

    private void updateSharedFilesPaginationControls() {
        updatePaginationControls(sharedPageLabel, sharedPrevButton, sharedNextButton,
                sharedFilesPage, sharedFilesTotalPages, sharedFilesTotalItems);
    }

    private static void updatePaginationControls(Label pageLabel, Button previous, Button next,
                                                 int page, int totalPages, long totalItems) {
        pageLabel.setText(pageInfoText(page, totalPages, totalItems));
        previous.setDisable(page <= 0);
        next.setDisable(totalPages == 0 || page + 1 >= totalPages);
    }

    private static void setPaginationBusy(Button previous, Button next) {
        previous.setDisable(true);
        next.setDisable(true);
    }

    /** Number of pages, or {@code 0} when there are no rows. */
    static int totalPages(long totalItems, int pageSize) {
        if (totalItems <= 0 || pageSize <= 0) {
            return 0;
        }
        return (int) ((totalItems - 1) / pageSize + 1);
    }

    /** Clamps a requested zero-based page into the valid range for the result set. */
    static int clampPage(int page, long totalItems, int pageSize) {
        int pages = totalPages(totalItems, pageSize);
        if (pages == 0) {
            return 0;
        }
        return Math.max(0, Math.min(page, pages - 1));
    }

    static int pageOffset(int page, int pageSize) {
        long offset = (long) page * pageSize;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, offset));
    }

    static String pageInfoText(int page, int totalPages, long totalItems) {
        int shownTotalPages = Math.max(totalPages, 1);
        int shownPage = totalPages == 0 ? 1 : Math.min(page + 1, totalPages);
        return "Page " + shownPage + " of " + shownTotalPages
                + " (Total: " + totalItems + " items)";
    }

    private String currentSearch() {
        if (searchField == null || searchField.getText() == null) {
            return null;
        }
        String trimmed = searchField.getText().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void submit(Task<?> task) {
        try {
            executor.submit(task);
        } catch (RejectedExecutionException e) {
            logger.debug("Ignoring refresh request after executor shutdown");
        }
    }

    private static String messageOf(Throwable throwable) {
        if (throwable == null) {
            return "Unknown error";
        }
        return throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
    }

    private record PageResult<T>(List<T> items, long totalItems, int page) { }

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
            myFilesPage = 0; // Newest files sort first.
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

    // ─── Session lifecycle / inactivity ─────────────────────────────────────

    private void startInactivityMonitoring() {
        stopInactivityMonitoring();

        inactivityService = InactivityService.fromConfiguration(this::onInactivityTimeout);

        if (welcomeLabel != null) {
            welcomeLabel.sceneProperty().addListener(sceneChangeListener);
            Scene scene = welcomeLabel.getScene();
            if (scene != null) {
                attachInactivityHandlers(scene);
            }
        }

        inactivityService.start();
    }

    private void attachInactivityHandlers(Scene scene) {
        if (scene == null || scene == inactivityScene) {
            return;
        }

        detachInactivityHandlers();
        inactivityScene = scene;
        scene.addEventFilter(MouseEvent.ANY, mouseActivityHandler);
        scene.addEventFilter(KeyEvent.ANY, keyActivityHandler);
        scene.addEventFilter(ScrollEvent.ANY, scrollActivityHandler);
    }

    private void detachInactivityHandlers() {
        if (inactivityScene == null) {
            return;
        }
        inactivityScene.removeEventFilter(MouseEvent.ANY, mouseActivityHandler);
        inactivityScene.removeEventFilter(KeyEvent.ANY, keyActivityHandler);
        inactivityScene.removeEventFilter(ScrollEvent.ANY, scrollActivityHandler);
        inactivityScene = null;
    }

    private void recordActivity() {
        if (inactivityService != null) {
            inactivityService.recordActivity();
        }
    }

    private void stopInactivityMonitoring() {
        if (welcomeLabel != null) {
            welcomeLabel.sceneProperty().removeListener(sceneChangeListener);
        }
        detachInactivityHandlers();
        if (inactivityService != null) {
            inactivityService.close();
            inactivityService = null;
        }
    }

    private void onInactivityTimeout() {
        Platform.runLater(() -> {
            if (currentUser == null && secretKey == null && privateKey == null) {
                return; // Already logged out.
            }
            try {
                performLogout(true);
            } catch (Exception e) {
                logger.error("Failed to complete inactivity logout", e);
                clearSessionSecrets();
            }
        });
    }

    @FXML
    private void handleLogout() throws Exception {
        performLogout(false);
    }

    private void performLogout(boolean sessionExpired) throws Exception {
        stopInactivityMonitoring();
        clearSessionSecrets();
        // Discard results from page loads still in flight for this session.
        myFilesLoadGeneration++;
        sharedFilesLoadGeneration++;
        executor.shutdown();
        MainApp.setRoot("ui/login");
        if (sessionExpired) {
            MainApp.showLoginNotice("Session expired due to inactivity.");
        }
    }

    /**
     * Destroys key material and drops all session references. Kept
     * package-private so lifecycle behaviour can be verified in tests.
     */
    void clearSessionSecrets() {
        if (secretKey != null) {
            try {
                secretKey.destroy();
            } catch (DestroyFailedException | RuntimeException e) {
                logger.warn("Could not destroy master secret key", e);
            }
        }
        if (privateKey != null) {
            try {
                privateKey.destroy();
            } catch (DestroyFailedException | RuntimeException e) {
                logger.warn("Could not destroy RSA private key", e);
            }
        }

        this.secretKey = null;
        this.privateKey = null;
        this.currentUser = null;

        // Encourage prompt reclamation of key material after logout.
        System.gc();
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
