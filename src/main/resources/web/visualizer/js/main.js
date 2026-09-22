'use strict';

/**
 * @file Main entry point for the visualizer application.
 * Initializes all components: AppController, HelpOverlay, AppSwitcher, and RunSelectorPanel.
 */
import { AppController } from './AppController.js';
import { AppSwitcher } from '../../shared/app-switcher/AppSwitcher.js';
import { RunSelectorPanel } from './ui/panels/RunSelectorPanel.js';
import { showLoadFailedNotice } from '../../shared/run/RunAvailability.js';
import { createWheelInputSwitch } from './ui/WheelInputSwitch.js';
import { HelpOverlay } from './ui/HelpOverlay.js';

// App controller instance (created after DOM is ready)
export let appController = null;

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
                tick: appController?.state?.currentTick,
                organism: appController?.state?.selectedOrganismId,
                runId: appController?.state?.runId
            }),
            footer: createWheelInputSwitch(mode => appController?.renderer?.interaction?.setWheelInputMode(mode))
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
        // Create the app controller (DOM is now ready)
        appController = new AppController();

        new HelpOverlay({
            button: document.getElementById('help-button'),
            overlay: document.getElementById('help-overlay'),
            anchor: document.getElementById('logo-panel')
        });

        // Initialize shared components in parallel
        await Promise.all([
            initAppSwitcher(),
            initRunSelector()
        ]);

        // Initialize the main controller
        await appController.init();
    } catch (error) {
        console.error('Failed to initialize visualizer:', error);
        showLoadFailedNotice('Could not start the visualizer', error);
    }
}

// Start initialization when DOM is ready
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}
