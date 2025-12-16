/**
 * Manages the header bar component, including tick navigation controls,
 * the organism selector, and associated keyboard shortcuts.
 *
 * @class HeaderbarView
 */
export class HeaderbarView {
    /**
     * Initializes the HeaderbarView and sets up all event listeners.
     * @param {AppController} controller - The main application controller.
     */
    constructor(controller) {
        this.controller = controller;
        
        // Debouncing for keyboard navigation
        this.keyRepeatTimeout = null;
        this.keyRepeatInterval = null;
        this.isKeyHeld = false;
        
        // Button event listeners
        document.getElementById('btn-prev').addEventListener('click', () => {
            this.controller.navigateToTick(this.controller.state.currentTick - 1);
        });
        
        document.getElementById('btn-next').addEventListener('click', () => {
            this.controller.navigateToTick(this.controller.state.currentTick + 1);
        });
        
        document.getElementById('btn-zoom-toggle').addEventListener('click', () => {
            this.controller.toggleZoom();
        });
        
        const input = document.getElementById('tick-input');
        
        // Input field event listeners
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                const v = parseInt(input.value, 10);
                if (!Number.isNaN(v)) {
                    this.controller.navigateToTick(v);
                    // Select all text after navigation so user can immediately type new number
                    setTimeout(() => input.select(), 0);
                }
            } else if (e.key === 'ArrowRight' || e.key === 'ArrowLeft' || e.key === 'ArrowUp' || e.key === 'ArrowDown') {
                // Allow arrow keys to navigate even when input is focused
                e.preventDefault();
                this.handleGlobalKeyDown(e);
                // Ensure input stays focused and text is selected
                setTimeout(() => {
                    input.focus();
                    input.select();
                }, 0);
            } else if (e.key === 'Escape') {
                // Escape: Blur the input field to exit focus
                e.preventDefault();
                input.blur();
            }
        });
        
        input.addEventListener('change', () => {
            const v = parseInt(input.value, 10);
            if (!Number.isNaN(v)) {
                this.controller.navigateToTick(v);
            }
        });
        
        // Input field click - select all text
        input.addEventListener('click', () => {
            input.select();
        });
        
        // Global keyboard shortcuts
        document.addEventListener('keydown', (e) => this.handleGlobalKeyDown(e));
        
        document.addEventListener('keyup', (e) => {
            if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(e.key)) {
                this.handleKeyRelease();
            }
        });
        
        // Reset keyboard events when window loses focus
        window.addEventListener('blur', () => {
            this.handleKeyRelease();
        });
    }
    
    /**
     * Updates the text of the zoom button based on the current zoom state.
     * @param {boolean} isZoomedOut - True if the view is zoomed out.
     */
    updateZoomButton(isZoomedOut) {
        const button = document.getElementById('btn-zoom-toggle');
        if (button) {
            button.textContent = isZoomedOut ? 'Zoom In' : 'Zoom Out';
        }
    }

    /**
     * Central handler for global keydown events, routing to the correct action.
     * @param {KeyboardEvent} e The keyboard event.
     * @private
     */
    handleGlobalKeyDown(e) {
        const input = document.getElementById('tick-input');
        // Ignore most shortcuts if a text input is focused, except for our navigation keys
        if (document.activeElement === input && !['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Enter', 'Escape'].includes(e.key)) {
            return;
        }

        switch (e.key) {
            case 'ArrowRight':
                e.preventDefault();
                this.handleKeyPress('forward');
                break;
            case 'ArrowLeft':
                e.preventDefault();
                this.handleKeyPress('backward');
                break;
            case 'ArrowUp':
                e.preventDefault();
                this.controller.navigateToTick(this.controller.state.currentTick + 1000);
                break;
            case 'ArrowDown':
                e.preventDefault();
                this.controller.navigateToTick(this.controller.state.currentTick - 1000);
                break;
        }
    }

    /**
     * Updates the displayed tick number in the input field and the total tick suffix.
     * 
     * @param {number} currentTick - The current tick number to display.
     * @param {number|null} maxTick - The maximum available tick, or null if not yet known.
     */
    updateTickDisplay(currentTick, maxTick) {
        const input = document.getElementById('tick-input');
        const suffix = document.getElementById('tick-total-suffix');
        
        if (input) {
            input.value = String(currentTick || 0);
            if (typeof maxTick === 'number' && maxTick > 0) {
                input.max = String(Math.max(0, maxTick));
            }
            
            // Select text if panel is visible (so user can immediately type new tick)
            const tickPanel = document.getElementById('tick-panel');
            if (tickPanel && tickPanel.style.display !== 'none') {
                setTimeout(() => {
                    input.focus();
                    input.select();
                }, 0);
            }
        }
        
        if (suffix) {
            suffix.textContent = '/' + (maxTick != null ? maxTick : 'N/A');
        }
    }
    
    /**
     * Handles the initial press of a navigation key (space or backspace).
     * It triggers an immediate navigation action and sets up timeouts/intervals for
     * continuous navigation if the key is held down.
     *
     * @param {('forward'|'backward')} direction - The direction to navigate.
     * @private
     */
    handleKeyPress(direction) {
        // If key is already being held, don't start a new sequence
        if (this.isKeyHeld) return;
        
        this.isKeyHeld = true;
        
        // Immediate first action
        this.navigateInDirection(direction);
        
        // Set up repeat after initial delay
        this.keyRepeatTimeout = setTimeout(() => {
            // Start repeating at regular intervals
            this.keyRepeatInterval = setInterval(() => {
                this.navigateInDirection(direction);
            }, 100); // 100ms between repeats (10 ticks per second)
        }, 300); // 300ms initial delay
    }
    
    /**
     * Handles the release of a navigation key.
     * Clears all active timeouts and intervals to stop continuous navigation.
     * @private
     */
    handleKeyRelease() {
        this.isKeyHeld = false;
        
        // Clear any pending timeouts/intervals
        if (this.keyRepeatTimeout) {
            clearTimeout(this.keyRepeatTimeout);
            this.keyRepeatTimeout = null;
        }
        if (this.keyRepeatInterval) {
            clearInterval(this.keyRepeatInterval);
            this.keyRepeatInterval = null;
        }
    }
    
    /**
     * Navigates to the next or previous tick via the controller.
     * @param {('forward'|'backward')} direction - The direction to navigate.
     * @private
     */
    navigateInDirection(direction) {
        if (direction === 'forward') {
            this.controller.navigateToTick(this.controller.state.currentTick + 1);
        } else if (direction === 'backward') {
            this.controller.navigateToTick(this.controller.state.currentTick - 1);
        }
    }
}

