/**
 * Manages the header bar component, including tick navigation controls,
 * the organism selector, and associated keyboard shortcuts.
 *
 * @class HeaderbarView
 */
class HeaderbarView {
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
            } else if (e.key === ' ') {
                // Space: Navigate to next tick even when input is focused
                e.preventDefault();
                this.handleKeyPress('forward');
                // Select all text after navigation so user can immediately type new number
                // Ensure input stays focused and text is selected even if it wasn't selected before
                setTimeout(() => {
                    input.focus();
                    input.select();
                }, 0);
            } else if (e.key === 'Escape') {
                // Escape: Blur the input field to exit focus
                e.preventDefault();
                input.blur();
            }
            // Backspace is NOT handled here - let it work normally (delete text)
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
        
        // Keyboard shortcuts with proper debouncing
        document.addEventListener('keydown', (e) => {
            // Don't handle backspace when input field is focused (let it delete text)
            if (document.activeElement === input && e.key === 'Backspace') {
                return; // Let backspace work normally in input field
            }
            
            // Don't handle space when input field is focused (space is handled in input's keydown)
            // The input handler will prevent default and handle navigation
            if (document.activeElement === input) {
                return; // Let input handler deal with it (especially space)
            }
            
            // Only handle shortcuts when input field is not focused
            if (e.key === ' ') {
                e.preventDefault(); // Prevent page scroll
                this.handleKeyPress('forward');
                // Focus and select input field after navigation so user can immediately type new tick number
                setTimeout(() => {
                    input.focus();
                    input.select();
                }, 0);
            } else if (e.key === 'Backspace') {
                e.preventDefault(); // Prevent browser back
                this.handleKeyPress('backward');
            }
        });
        
        document.addEventListener('keyup', (e) => {
            if (e.key === ' ' || e.key === 'Backspace') {
                this.handleKeyRelease();
            }
        });
        
        // Reset keyboard events when window loses focus
        window.addEventListener('blur', () => {
            this.handleKeyRelease();
        });
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

// Export for global availability
window.HeaderbarView = HeaderbarView;

