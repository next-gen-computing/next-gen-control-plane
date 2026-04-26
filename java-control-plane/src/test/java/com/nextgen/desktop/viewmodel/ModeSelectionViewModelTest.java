package com.nextgen.desktop.viewmodel;

import com.nextgen.desktop.service.ConfigurationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ModeSelectionViewModel.
 */
class ModeSelectionViewModelTest {
    
    @Mock
    private ConfigurationService configurationService;
    
    private ModeSelectionViewModel viewModel;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        viewModel = new ModeSelectionViewModel(configurationService);
    }
    
    @Test
    void testInitialState() {
        assertEquals("", viewModel.getSelectedMode());
        assertFalse(viewModel.isServerModeSelected());
        assertFalse(viewModel.isNodeModeSelected());
        assertFalse(viewModel.isLoading());
        assertFalse(viewModel.hasError());
    }
    
    @Test
    void testSelectServerMode() {
        viewModel.selectServerMode();
        
        assertEquals("SERVER", viewModel.getSelectedMode());
        assertTrue(viewModel.isServerModeSelected());
        assertFalse(viewModel.isNodeModeSelected());
    }
    
    @Test
    void testSelectNodeMode() {
        viewModel.selectNodeMode();
        
        assertEquals("NODE", viewModel.getSelectedMode());
        assertFalse(viewModel.isServerModeSelected());
        assertTrue(viewModel.isNodeModeSelected());
    }
    
    @Test
    void testConfirmSelectionWithNoMode() {
        final boolean[] serverCalled = {false};
        final boolean[] nodeCalled = {false};
        
        viewModel.confirmSelection(
            () -> serverCalled[0] = true,
            () -> nodeCalled[0] = true
        );
        
        assertTrue(viewModel.hasError());
        assertEquals("Please select a mode", viewModel.getErrorMessage());
        assertFalse(serverCalled[0]);
        assertFalse(nodeCalled[0]);
    }
    
    @Test
    void testConfirmSelectionWithServerMode() {
        viewModel.selectServerMode();
        
        final boolean[] serverCalled = {false};
        final boolean[] nodeCalled = {false};
        
        viewModel.confirmSelection(
            () -> serverCalled[0] = true,
            () -> nodeCalled[0] = true
        );
        
        assertFalse(viewModel.hasError());
        assertTrue(serverCalled[0]);
        assertFalse(nodeCalled[0]);
    }
    
    @Test
    void testConfirmSelectionWithNodeMode() {
        viewModel.selectNodeMode();
        
        final boolean[] serverCalled = {false};
        final boolean[] nodeCalled = {false};
        
        viewModel.confirmSelection(
            () -> serverCalled[0] = true,
            () -> nodeCalled[0] = true
        );
        
        assertFalse(viewModel.hasError());
        assertFalse(serverCalled[0]);
        assertTrue(nodeCalled[0]);
    }
    
    @Test
    void testModeChangeUpdatesProperties() {
        assertFalse(viewModel.isServerModeSelected());
        assertFalse(viewModel.isNodeModeSelected());
        
        viewModel.selectServerMode();
        assertTrue(viewModel.isServerModeSelected());
        assertFalse(viewModel.isNodeModeSelected());
        
        viewModel.selectNodeMode();
        assertFalse(viewModel.isServerModeSelected());
        assertTrue(viewModel.isNodeModeSelected());
    }
}
