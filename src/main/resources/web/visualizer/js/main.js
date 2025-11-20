'use strict';

/**
 * @file Main entry point for the visualizer application.
 * This script sets up global utilities like the error banner and then fires a 'uiReady'
 * event to signal that the main application controller can be initialized.
 * It must be the last script loaded to ensure the DOM is fully parsed.
 */
document.addEventListener('DOMContentLoaded', () => {
    // Setup global error banner logic
    const errorBanner = document.getElementById('error-banner');
    const errorMessageSpan = document.getElementById('error-message');
    const closeButton = document.getElementById('close-error-banner');
    
    // Define global error functions BEFORE initializing the app
    window.showError = (message) => {
        errorMessageSpan.textContent = message;
        // First, remove the inline style to allow the CSS class to take effect.
        errorBanner.style.display = ''; 
        // Then, add the class that makes the banner visible via the stylesheet.
        errorBanner.classList.add('error-bar-visible');
    };

    window.hideError = () => {
        // Use the CSS class to hide the banner
        errorBanner.classList.remove('error-bar-visible');
        errorMessageSpan.textContent = '';
    };

    closeButton.addEventListener('click', window.hideError);

    /**
     * A custom event fired when the DOM is ready and global helpers (like showError)
     * have been initialized. The AppController listens for this event to begin its
     * own initialization sequence.
     * @event uiReady
     */
    document.dispatchEvent(new Event('uiReady'));
});

