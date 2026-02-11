'use strict';

/**
 * @file Main entry point for the visualizer application.
 * Initializes all components: AppController, AppSwitcher, and RunSelectorPanel.
 */
import { AppController } from './AppController.js';
import { AppSwitcher } from '../../shared/app-switcher/AppSwitcher.js';
import { RunSelectorPanel } from './ui/panels/RunSelectorPanel.js';

// App controller instance (created after DOM is ready)
export let appController = null;

/**
 * Sets up global error handling functions.
 */
function setupErrorHandling() {
    const errorBanner = document.getElementById('error-banner');
    const errorMessageSpan = document.getElementById('error-message');
    const closeButton = document.getElementById('close-error-banner');

    window.showError = (message) => {
        if (errorMessageSpan && errorBanner) {
            errorMessageSpan.textContent = message;
            errorBanner.style.display = 'flex';
        }
    };

    window.hideError = () => {
        if (errorBanner && errorMessageSpan) {
            errorBanner.style.display = 'none';
            errorMessageSpan.textContent = '';
        }
    };

    if (closeButton) {
        closeButton.addEventListener('click', window.hideError);
    }

    // Close error modal with ESC key
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && errorBanner && errorBanner.style.display !== 'none') {
            window.hideError();
        }
    });

    // Close error modal when clicking on backdrop
    errorBanner?.addEventListener('click', (e) => {
        if (e.target === errorBanner) {
            window.hideError();
        }
    });
}

/**
 * Initializes the AppSwitcher component.
 */
async function initAppSwitcher() {
    try {
        const container = document.getElementById('app-switcher-container');
        if (!container) return;

        const response = await fetch('../shared/app-switcher/apps.json');
        const apps = await response.json();

        const switcher = new AppSwitcher({
            element: container,
            apps: apps,
            getState: () => ({
                runId: appController?.state?.runId,
                tick: appController?.state?.currentTick
            })
        });

        // Make the entire logo panel trigger the app switcher
        const logoPanel = document.getElementById('logo-panel');
        if (logoPanel) {
            logoPanel.style.cursor = 'pointer';
            logoPanel.addEventListener('click', (e) => {
                if (e.target.closest('.app-switcher-button')) return;
                if (e.target.closest('.app-switcher-overlay')) return;
                e.stopPropagation();
                switcher.toggleOverlay();
            });
        }
    } catch (error) {
        console.error('Failed to initialize AppSwitcher:', error);
    }
}

/**
 * Initializes the Run Selector Panel.
 */
async function initRunSelector() {
    try {
        const fetchRuns = async () => {
            const response = await fetch('/analyzer/api/runs');
            if (!response.ok) {
                const text = await response.text();
                throw new Error(text || 'Failed to fetch runs');
            }
            return response.json();
        };

        window.runSelectorPanel = new RunSelectorPanel({
            fetchRuns,
            getCurrentRunId: () => appController?.state?.runId || null,
            onRunChange: (runId) => appController?.changeRun(runId)
        });
    } catch (error) {
        console.error('Failed to initialize RunSelectorPanel:', error);
    }
}

/**
 * Main initialization function.
 */
async function init() {
    try {
        // Set up error handling first
        setupErrorHandling();

        // Create the app controller (DOM is now ready)
        appController = new AppController();

        // Initialize shared components in parallel
        await Promise.all([
            initAppSwitcher(),
            initRunSelector()
        ]);

        // Initialize the main controller
        await appController.init();
    } catch (error) {
        console.error('Failed to initialize visualizer:', error);
        window.showError?.('Failed to initialize visualizer: ' + error.message);
    }
}

// Start initialization when DOM is ready
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}
