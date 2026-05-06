package com.nextgen.desktop.ui.view;

import com.nextgen.desktop.ui.model.TaskModel;
import com.nextgen.desktop.ui.service.NodeMonitoringService;
import com.nextgen.desktop.ui.service.TaskExecutionService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Task submission and execution tracking screen.
 */
public class TaskSubmissionView {
    private final TaskExecutionService taskService;
    private final NodeMonitoringService monitoringService;
    private final VBox root;
    private final VBox tasksContainer;

    public TaskSubmissionView(TaskExecutionService taskService, NodeMonitoringService monitoringService) {
        this.taskService = taskService;
        this.monitoringService = monitoringService;
        this.root = createView();
        this.tasksContainer = (VBox) root.lookup("#tasksContainer");
        bindData();
    }

    public VBox getRoot() {
        return root;
    }

    private VBox createView() {
        VBox view = new VBox(24);
        view.setPadding(new Insets(10));
        view.getStyleClass().add("task-submission-view");

        // Title
        Label title = new Label("Task Execution");
        title.setFont(Font.font("Inter", FontWeight.BOLD, 32));
        title.setTextFill(Color.web("#f8fafc"));

        // Task form
        VBox form = createTaskForm();

        // Active tasks section
        Label activeTitle = new Label("Active Tasks");
        activeTitle.setFont(Font.font("Inter", FontWeight.BOLD, 20));
        activeTitle.setTextFill(Color.web("#f8fafc"));

        VBox tasks = new VBox(12);
        tasks.setId("tasksContainer");
        tasks.setPadding(new Insets(10));

        ScrollPane scrollPane = new ScrollPane(tasks);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        view.getChildren().addAll(title, form, activeTitle, scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        return view;
    }

    private VBox createTaskForm() {
        VBox form = new VBox(16);
        form.setPadding(new Insets(20));
        form.setStyle("-fx-background-color: #1e293b; -fx-background-radius: 12px;");

        Label formTitle = new Label("Submit New Task");
        formTitle.setFont(Font.font("Inter", FontWeight.BOLD, 18));
        formTitle.setTextFill(Color.web("#f8fafc"));

        // Task type
        HBox typeRow = new HBox(12);
        typeRow.setAlignment(Pos.CENTER_LEFT);

        Label typeLabel = new Label("Task Type:");
        typeLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        typeLabel.setTextFill(Color.web("#94a3b8"));
        typeLabel.setPrefWidth(100);

        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("Matrix Multiplication", "Large Array Sum", "Prime Counter");
        typeCombo.setValue("Matrix Multiplication");
        typeCombo.setStyle("-fx-background-color: #334155; -fx-text-fill: #f8fafc; -fx-background-radius: 8px;");
        typeCombo.setPrefWidth(250);

        typeRow.getChildren().addAll(typeLabel, typeCombo);

        // Parameters
        HBox paramsRow = new HBox(12);
        paramsRow.setAlignment(Pos.CENTER_LEFT);

        Label paramsLabel = new Label("Parameters:");
        paramsLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 14));
        paramsLabel.setTextFill(Color.web("#94a3b8"));
        paramsLabel.setPrefWidth(100);

        TextField paramsField = new TextField("1024");
        paramsField.setPromptText("e.g., 1024 for matrix size, or range like 1-1000000");
        paramsField.setStyle("-fx-background-color: #334155; -fx-text-fill: #f8fafc; -fx-background-radius: 8px;");
        paramsField.setPrefWidth(250);

        paramsRow.getChildren().addAll(paramsLabel, paramsField);

        // Submit button
        Button submitButton = new Button("Submit Task");
        submitButton.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-background-radius: 8px; -fx-padding: 10px 30px; -fx-cursor: hand; -fx-font-weight: bold;");
        submitButton.setOnAction(e -> {
            TaskModel.TaskType type = switch (typeCombo.getValue()) {
                case "Matrix Multiplication" -> TaskModel.TaskType.MATRIX_MULTIPLICATION;
                case "Large Array Sum" -> TaskModel.TaskType.LARGE_ARRAY_SUM;
                case "Prime Counter" -> TaskModel.TaskType.PRIME_COUNTER;
                default -> TaskModel.TaskType.MATRIX_MULTIPLICATION;
            };
            taskService.submitTask(type, paramsField.getText());
        });

        form.getChildren().addAll(formTitle, typeRow, paramsRow, submitButton);
        return form;
    }

    private void bindData() {
        taskService.getTasks().addListener((javafx.collections.ListChangeListener<TaskModel>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    for (TaskModel task : change.getAddedSubList()) {
                        javafx.application.Platform.runLater(() -> addTaskRow(task));
                    }
                }
            }
        });
    }

    private void addTaskRow(TaskModel task) {
        VBox row = new VBox(10);
        row.setPadding(new Insets(16));
        row.setStyle("-fx-background-color: #1e293b; -fx-background-radius: 12px;");
        row.setId("task-" + task.getId());

        // Header
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        Label typeLabel = new Label(task.getTypeDisplayName());
        typeLabel.setFont(Font.font("Inter", FontWeight.BOLD, 14));
        typeLabel.setTextFill(Color.web("#f8fafc"));

        Label statusLabel = new Label(task.getStatusDisplayName());
        statusLabel.setFont(Font.font("Inter", FontWeight.BOLD, 12));
        statusLabel.setTextFill(getStatusColor(task.getStatus()));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label idLabel = new Label("ID: " + task.getId());
        idLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 11));
        idLabel.setTextFill(Color.web("#64748b"));

        header.getChildren().addAll(typeLabel, statusLabel, spacer, idLabel);

        // Progress
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(400);
        progressBar.setStyle("-fx-accent: #3b82f6;");
        progressBar.setId("progress-" + task.getId());

        Label progressLabel = new Label("0%");
        progressLabel.setFont(Font.font("Inter", FontWeight.BOLD, 12));
        progressLabel.setTextFill(Color.web("#94a3b8"));
        progressLabel.setId("progress-label-" + task.getId());

        HBox progressRow = new HBox(10, progressBar, progressLabel);
        progressRow.setAlignment(Pos.CENTER_LEFT);

        // Details
        Label detailsLabel = new Label("Params: " + task.getPayload());
        detailsLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 12));
        detailsLabel.setTextFill(Color.web("#64748b"));

        Label resultLabel = new Label();
        resultLabel.setFont(Font.font("Inter", FontWeight.NORMAL, 12));
        resultLabel.setTextFill(Color.web("#10b981"));
        resultLabel.setWrapText(true);
        resultLabel.setId("result-" + task.getId());

        row.getChildren().addAll(header, progressRow, detailsLabel, resultLabel);

        // Bind progress
        task.progressProperty().addListener((obs, old, val) -> {
            javafx.application.Platform.runLater(() -> {
                progressBar.setProgress(val.doubleValue() / 100.0);
                progressLabel.setText(String.format("%.0f%%", val.doubleValue()));
            });
        });

        task.statusProperty().addListener((obs, old, val) -> {
            javafx.application.Platform.runLater(() -> {
                statusLabel.setText(task.getStatusDisplayName());
                statusLabel.setTextFill(getStatusColor(val));
                if (val == TaskModel.TaskStatus.COMPLETED) {
                    Label resLbl = (Label) row.lookup("#result-" + task.getId());
                    if (resLbl != null) resLbl.setText("Result: " + task.getResult());
                }
            });
        });

        tasksContainer.getChildren().add(0, row);
    }

    private Color getStatusColor(TaskModel.TaskStatus status) {
        return switch (status) {
            case PENDING -> Color.web("#94a3b8");
            case RUNNING -> Color.web("#3b82f6");
            case COMPLETED -> Color.web("#10b981");
            case FAILED -> Color.web("#ef4444");
        };
    }
}
