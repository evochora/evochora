'use strict';

/**
 * Main entry point for the visualizer application.
 */
document.addEventListener('DOMContentLoaded', () => {
    // Setup error banner logic
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

    // Fire a custom event to signal that basic UI functions are ready
    document.dispatchEvent(new Event('uiReady'));
});

