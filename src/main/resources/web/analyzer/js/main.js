/**
 * Evochora Analytics Dashboard - Main Entry Point
 * 
 * This is the single entry point for the application.
 * It imports all modules and initializes the application.
 * 
 * @module main
 */

// Import chart modules (they self-register with ChartRegistry)
import './charts/LineChart.js';
import './charts/BarChart.js';
import './charts/HeatmapChart.js';
import './charts/StackedAreaChart.js';
import './charts/StackedBarChart.js';
import './charts/BandChart.js';

// Import main application modules
import * as AnalyzerController from './AnalyzerController.js';
import * as AnalyticsApi from './api/AnalyticsApi.js';
import * as HeaderView from './ui/HeaderView.js';

// Import shared components
import { Footer } from '../../shared/footer/Footer.js';
import { AppSwitcher } from '../../shared/app-switcher/AppSwitcher.js';

/**
 * Initializes the application when the DOM is ready.
 */
    function initApp() {
        // Setup error bar close button
        const closeErrorBtn = document.getElementById('close-error-banner');
        if (closeErrorBtn) {
            closeErrorBtn.addEventListener('click', () => {
                AnalyzerController.hideError();
            });
        }
    
    // Initialize shared components
    initAppSwitcher();
    initFooter();
        
        // Initialize the main controller
        AnalyzerController.init().catch(error => {
            console.error('[Analytics] Initialization failed:', error);
            AnalyzerController.showError(`Initialization failed: ${error.message}`);
        });
    }
    
/**
 * Initializes the app switcher component.
 */
function initAppSwitcher() {
    const container = document.getElementById('app-switcher-container');
    if (!container) {
        console.warn('[Analytics] App switcher container not found');
        return;
    }
    
    function getAppState() {
        const params = new URLSearchParams(window.location.search);
        return {
            tick: params.get('tick'),
            organism: params.get('organism'),
            runId: params.get('runId')
        };
    }
    
    fetch('../shared/app-switcher/apps.json')
        .then(res => res.json())
        .then(apps => {
            new AppSwitcher({
                element: container,
                apps: apps,
                getState: getAppState
            });
        })
        .catch(err => console.error('[Analytics] Failed to load app switcher:', err));
}

/**
 * Initializes the footer component.
 */
function initFooter() {
    const footerContainer = document.getElementById('footer-container');
    if (!footerContainer) {
        console.warn('[Analytics] Footer container not found');
        return;
    }
    
    const fetchRuns = async () => {
        return await AnalyticsApi.listRuns();
    };
    
    window.footer = new Footer({
        element: footerContainer,
        fetchRuns,
        getCurrentRunId: () => AnalyzerController.getCurrentRunId(),
        onRunChange: async (runId) => {
            await AnalyzerController.changeRun(runId);
            window.footer?.updateCurrent?.();
        },
    });
}

// Initialize when DOM is ready
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initApp);
} else {
    initApp();
}

// Export for external access (e.g., from inline scripts)
export { AnalyzerController, AnalyticsApi, HeaderView };
