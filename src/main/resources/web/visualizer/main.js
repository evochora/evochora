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
        errorBanner.style.display = 'flex';
    };

    window.hideError = () => {
        errorBanner.style.display = 'none';
        errorMessageSpan.textContent = '';
    };

    closeButton.addEventListener('click', window.hideError);

    // Fire a custom event to signal that basic UI functions are ready
    document.dispatchEvent(new Event('uiReady'));
});

