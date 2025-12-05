'use strict';

/**
 * @file Main entry point for the visualizer application.
 * Initializes the main AppController which orchestrates the entire UI.
 */
import { AppController } from './AppController.js';

// Make the appController instance available for the AppSwitcher
export const appController = new AppController();

document.addEventListener('DOMContentLoaded', () => {
    // Setup global error banner logic
    const errorBanner = document.getElementById('error-banner');
    const errorMessageSpan = document.getElementById('error-message');
    const closeButton = document.getElementById('close-error-banner');
    
    // Define global error functions BEFORE initializing the app
    window.showError = (message) => {
        errorMessageSpan.textContent = message;
        errorBanner.style.display = 'flex';
    };

    window.hideError = () => {
        errorBanner.style.display = 'none';
        errorMessageSpan.textContent = '';
    };

    closeButton.addEventListener('click', window.hideError);

    // Initialize the main controller
    appController.init().catch(error => {
        console.error('Failed to initialize visualizer:', error);
        window.showError('Failed to initialize visualizer: ' + error.message);
    });
});

