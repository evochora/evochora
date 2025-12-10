export class AppSwitcher {
    constructor({ element, apps, getState }) {
        this.element = element;
        this.apps = apps;
        this.getState = getState;
        this.isOverlayVisible = false;
        this.render();
        this.attachEventListeners();
    }

    render() {
        this.element.innerHTML = `
            <div class="app-switcher-button" title="Switch App">
                <svg viewBox="0 0 24 24">
                    <path d="M4,4H8V8H4V4M10,4H14V8H10V4M16,4H20V8H16V4M4,10H8V14H4V10M10,10H14V14H10V10M16,10H20V14H16V10M4,16H8V20H4V16M10,16H14V20H10V16M16,16H20V20H16V16Z" />
                </svg>
            </div>
            <div class="app-switcher-overlay"></div>
        `;
        this.overlay = this.element.querySelector('.app-switcher-overlay');
        this.renderOverlayContent();
    }

    renderOverlayContent() {
        const currentUrl = window.location.pathname;
        const appLinks = this.apps
            .filter(app => !currentUrl.includes(app.url))
            .map(app => {
                const link = document.createElement('a');
                link.href = this.buildUrl(app.url);
                link.innerHTML = `
                    <div class="app-name">${app.name}</div>
                    <div class="app-description">${app.description}</div>
                `;
                return link;
            });

        this.overlay.innerHTML = '';
        appLinks.forEach(link => this.overlay.appendChild(link));
    }

    buildUrl(baseUrl) {
        const state = this.getState();
        const params = new URLSearchParams();
        if (state) {
            Object.entries(state).forEach(([key, value]) => {
                if (value !== null && value !== undefined) {
                    params.set(key, value);
                }
            });
        }
        const queryString = params.toString();
        return queryString ? `${baseUrl}?${queryString}` : baseUrl;
    }

    toggleOverlay() {
        this.isOverlayVisible = !this.isOverlayVisible;
        this.overlay.classList.toggle('visible', this.isOverlayVisible);
        if (this.isOverlayVisible) {
            // Update links every time overlay is opened to get fresh state
            this.renderOverlayContent();
        }
    }

    attachEventListeners() {
        const button = this.element.querySelector('.app-switcher-button');
        button.addEventListener('click', (e) => {
            e.stopPropagation();
            this.toggleOverlay();
        });

        document.addEventListener('click', (e) => {
            if (this.isOverlayVisible && !this.overlay.contains(e.target)) {
                this.toggleOverlay();
            }
        });
    }
}




