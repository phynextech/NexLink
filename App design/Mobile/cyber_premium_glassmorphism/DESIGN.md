---
name: Cyber-Premium Glassmorphism
colors:
  surface: '#091421'
  surface-dim: '#091421'
  surface-bright: '#303a49'
  surface-container-lowest: '#050f1c'
  surface-container-low: '#121c2a'
  surface-container: '#16202e'
  surface-container-high: '#212a39'
  surface-container-highest: '#2b3544'
  on-surface: '#d9e3f7'
  on-surface-variant: '#bac9cc'
  inverse-surface: '#d9e3f7'
  inverse-on-surface: '#273140'
  outline: '#849396'
  outline-variant: '#3b494c'
  surface-tint: '#00daf3'
  primary: '#c3f5ff'
  on-primary: '#00363d'
  primary-container: '#00e5ff'
  on-primary-container: '#00626e'
  inverse-primary: '#006875'
  secondary: '#cdbdff'
  on-secondary: '#370096'
  secondary-container: '#5203d5'
  on-secondary-container: '#c0acff'
  tertiary: '#eaecfe'
  on-tertiary: '#2c303d'
  tertiary-container: '#cdd0e1'
  on-tertiary-container: '#555967'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#9cf0ff'
  primary-fixed-dim: '#00daf3'
  on-primary-fixed: '#001f24'
  on-primary-fixed-variant: '#004f58'
  secondary-fixed: '#e8deff'
  secondary-fixed-dim: '#cdbdff'
  on-secondary-fixed: '#20005f'
  on-secondary-fixed-variant: '#4f00d0'
  tertiary-fixed: '#dfe2f3'
  tertiary-fixed-dim: '#c3c6d7'
  on-tertiary-fixed: '#171b28'
  on-tertiary-fixed-variant: '#434654'
  background: '#091421'
  on-background: '#d9e3f7'
  surface-variant: '#2b3544'
typography:
  h1:
    fontFamily: Space Grotesk
    fontSize: 32px
    fontWeight: '700'
    lineHeight: '1.2'
    letterSpacing: -0.02em
  h2:
    fontFamily: Space Grotesk
    fontSize: 24px
    fontWeight: '700'
    lineHeight: '1.3'
  body-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 18px
    fontWeight: '400'
    lineHeight: '1.6'
  body-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 16px
    fontWeight: '400'
    lineHeight: '1.6'
  label-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 12px
    fontWeight: '600'
    lineHeight: '1'
  code-data:
    fontFamily: Space Grotesk
    fontSize: 14px
    fontWeight: '500'
    lineHeight: '1.4'
    letterSpacing: 0.05em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  base: 8px
  xs: 4px
  sm: 12px
  md: 24px
  lg: 40px
  xl: 64px
  margin-safe: 24px
  gutter: 16px
---

## Brand & Style

This design system is built to evoke a sense of high-performance connectivity and futuristic sophistication. The brand personality is "The Ethereal Poweruser"—combining the raw technical capability of a PC workstation with the fluid, tactile ease of a premium mobile experience.

The visual style is a specialized evolution of **Glassmorphism** mixed with **Retro-Futurism**. It utilizes deep, light-absorbing backgrounds to make vibrant neon accents "pop" with optical glow effects. The interface should feel like a holographic projection—translucent, layered, and responsive to touch, providing users with the emotional assurance of a seamless, high-speed link between their devices.

## Colors

The palette of this design system is anchored in "Deep Navy" to ensure infinite depth. The "Electric Cyan" primary color is used exclusively for active states, primary actions, and critical data points, while "Deep Purple" provides a sophisticated secondary bridge for gradients.

Surface colors utilize a tiered transparency model. The base surface is a 60% opaque navy, allowing background radial "auras" to subtly shimmer through the interface as the user scrolls. Semantic colors are highly saturated to remain legible against the dark backdrop and frosted glass layers.

## Typography

The typographic hierarchy prioritizes technical clarity and modern aesthetics. Headlines use a bold, geometric sans-serif to mirror the angular precision of high-end hardware. Body text utilizes a softer, more legible rounded sans-serif to ensure comfort during long sessions of file management or remote control.

A specialized "Data" style is reserved for IP addresses, MAC IDs, and system specs. This style uses increased letter spacing and a more rigid geometric construction to mimic a monospaced environment without sacrificing the premium look of the design system.

## Layout & Spacing

This design system employs a fluid, gesture-centric layout. By removing traditional navigation bars, the system maximizes vertical screen real estate for PC-to-Mobile content mirroring. 

The rhythm is dictated by an 8px base grid. Standard horizontal margins are set to 24px to prevent content from crowding the edges of modern edge-to-edge displays. Vertical spacing between glass cards should be 16px, creating a distinct sense of separation while maintaining a cohesive "stack." Components should rely on internal padding rather than external margins to define their clickable areas, facilitating the gesture-heavy interaction model.

## Elevation & Depth

Depth in this design system is achieved through "Optical Translucency" rather than traditional shadows. 

1.  **Z-Index 0 (Background):** Deep Navy solid color with localized Radial Glow auras that follow the user's focus or primary status.
2.  **Z-Index 1 (Surface Cards):** 60% opacity Navy with a 20px backdrop blur. A 1px border at 8-10% white opacity is required to define the edges against the dark background.
3.  **Z-Index 2 (Interactive Elements):** Pill buttons and active chips use vibrant gradients. These elements feature a "Neon Glow"—a drop shadow matching the primary cyan color with a 15px blur and 30% opacity, making them appear to emit light.
4.  **Z-Index 3 (Modals/Overlays):** Higher backdrop blur (40px) and a slightly thicker 1.5px border to indicate a higher level in the visual stack.

## Shapes

The shape language is a mix of "Stationary Containers" and "Action Elements." Large containers (cards, modals) use a 20px (rounded-xl) corner radius to feel approachable and modern. 

Interactive elements like buttons and input toggles are strictly pill-shaped (50px+ radius). This contrast between the structured cards and the organic, rounded buttons helps the user immediately identify "touchable" targets within the glass interface.

## Components

-   **Buttons:** Must be pill-shaped. Primary buttons use the Linear Cyan-to-Purple gradient with white text. Secondary buttons use a "Ghost" style: a 1px Cyan border with a subtle 10% Cyan fill.
-   **Glass Cards:** The primary container. Must include `backdrop-filter: blur(20px)` and a subtle internal 8% white border. No solid backgrounds.
-   **Data Chips:** Small, semi-transparent capsules (pill-shaped) used for displaying connection status (e.g., "5G", "Connected", "Latency: 12ms").
-   **Icons:** Material Symbols (Rounded). Stroke weight should be "Light" or "Regular." Icons are always tinted in Electric Cyan when active and Secondary Gray when inactive.
-   **Input Fields:** Bottom-aligned labels with a simple 1px Cyan underline that glows when focused. No box-style inputs to maintain the minimalist aesthetic.
-   **Connection Indicator:** A custom component featuring a pulsing radial aura around the device icon to signify an active PC-to-Mobile link.