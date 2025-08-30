class ToolbarView {
    constructor(controller) {
        this.controller = controller;
        
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
        
        // Keyboard shortcuts
        document.addEventListener('keydown', (e) => {
            // Only handle shortcuts when not typing in input field
            if (document.activeElement === input) return;
            
            if (e.key === ' ') {
                e.preventDefault(); // Prevent page scroll
                this.controller.navigateToTick(this.controller.state.currentTick + 1);
            } else if (e.key === 'Backspace') {
                e.preventDefault(); // Prevent browser back
                this.controller.navigateToTick(this.controller.state.currentTick - 1);
            }
        });
    }
}
