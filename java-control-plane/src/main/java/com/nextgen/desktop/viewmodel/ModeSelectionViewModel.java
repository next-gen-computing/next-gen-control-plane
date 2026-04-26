package com.nextgen.desktop.viewmodel;

import com.nextgen.desktop.service.ConfigurationService;
import javafx.beans.property.*;

/**
 * ViewModel for mode selection screen (Server vs Node mode).
 */
public class ModeSelectionViewModel extends BaseViewModel {
    
    private final ConfigurationService configurationService;
    
    // Selected mode: "SERVER" or "NODE"
    private final StringProperty selectedMode = new SimpleStringProperty("");
    private final BooleanProperty serverModeSelected = new SimpleBooleanProperty(false);
    private final BooleanProperty nodeModeSelected = new SimpleBooleanProperty(false);
    
    public ModeSelectionViewModel(ConfigurationService configurationService) {
        this.configurationService = configurationService;
        
        // Listen for mode changes
        selectedMode.addListener((obs, oldVal, newVal) -> {
            serverModeSelected.set("SERVER".equals(newVal));
            nodeModeSelected.set("NODE".equals(newVal));
        });
    }
    
    /**
     * Select Server mode.
     */
    public void selectServerMode() {
        selectedMode.set("SERVER");
        LOG.info("Server mode selected");
    }
    
    /**
     * Select Node mode.
     */
    public void selectNodeMode() {
        selectedMode.set("NODE");
        LOG.info("Node mode selected");
    }
    
    /**
     * Confirm mode selection and proceed.
     */
    public void confirmSelection(Runnable onServerSelected, Runnable onNodeSelected) {
        String mode = selectedMode.get();
        if (mode.isEmpty()) {
            setErrorMessage("Please select a mode");
            return;
        }
        
        clearError();
        if ("SERVER".equals(mode)) {
            onServerSelected.run();
        } else if ("NODE".equals(mode)) {
            onNodeSelected.run();
        }
    }
    
    // Property accessors
    public StringProperty selectedModeProperty() { return selectedMode; }
    public String getSelectedMode() { return selectedMode.get(); }
    
    public BooleanProperty serverModeSelectedProperty() { return serverModeSelected; }
    public boolean isServerModeSelected() { return serverModeSelected.get(); }
    
    public BooleanProperty nodeModeSelectedProperty() { return nodeModeSelected; }
    public boolean isNodeModeSelected() { return nodeModeSelected.get(); }
}
