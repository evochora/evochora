class ToolbarView {
    constructor(controller) {
        this.controller = controller;
        
        // Debouncing for keyboard navigation
        this.keyRepeatTimeout = null;
        this.keyRepeatInterval = null;
        this.isKeyHeld = false;
        
        // Button event listeners
        document.getElementById('btn-prev').addEventListener('click', () => this.controller.navigateToTick(this.controller.state.currentTick - 1));
        document.getElementById('btn-next').addEventListener('click', () => this.controller.navigateToTick(this.controller.state.currentTick + 1));
        
        const input = document.getElementById('tick-input');
        document.getElementById('btn-goto').addEventListener('click', () => {
            const v = parseInt(input.value, 10);
            if (!Number.isNaN(v)) this.controller.navigateToTick(v);
        });
        
        // Input field event listeners
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                const v = parseInt(input.value, 10);
                if (!Number.isNaN(v)) this.controller.navigateToTick(v);
            }
        });
        input.addEventListener('change', () => {
            const v = parseInt(input.value, 10);
            if (!Number.isNaN(v)) this.controller.navigateToTick(v);
        });
        
        // Input field click - select all text
        input.addEventListener('click', () => {
            input.select();
        });
        
        // Keyboard shortcuts with proper debouncing
        document.addEventListener('keydown', (e) => {
            // Only handle shortcuts when not typing in input field
            if (document.activeElement === input) return;
            
            if (e.key === ' ') {
                e.preventDefault(); // Prevent page scroll
                this.handleKeyPress('forward');
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
    
    navigateInDirection(direction) {
        if (direction === 'forward') {
            this.controller.navigateToTick(this.controller.state.currentTick + 1);
        } else if (direction === 'backward') {
            this.controller.navigateToTick(this.controller.state.currentTick - 1);
        }
    }
}
