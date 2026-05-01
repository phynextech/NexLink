---
name: NexLink Cyber-Premium
colors:
  surface: '#0f131f'
  surface-dim: '#0f131f'
  surface-bright: '#353946'
  surface-container-lowest: '#0a0e1a'
  surface-container-low: '#171b28'
  surface-container: '#1b1f2c'
  surface-container-high: '#262a37'
  surface-container-highest: '#313442'
  on-surface: '#dfe2f3'
  on-surface-variant: '#bac9cc'
  inverse-surface: '#dfe2f3'
  inverse-on-surface: '#2c303d'
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
  tertiary: '#ececec'
  on-tertiary: '#2f3131'
  tertiary-container: '#d0d0d0'
  on-tertiary-container: '#575959'
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
  tertiary-fixed: '#e2e2e2'
  tertiary-fixed-dim: '#c6c6c7'
  on-tertiary-fixed: '#1a1c1c'
  on-tertiary-fixed-variant: '#454747'
  background: '#0f131f'
  on-background: '#dfe2f3'
  surface-variant: '#313442'
typography:
  h1:
    fontFamily: Rajdhani
    fontSize: 40px
    fontWeight: '700'
    lineHeight: '1.2'
    letterSpacing: 0.02em
  h2:
    fontFamily: Rajdhani
    fontSize: 28px
    fontWeight: '600'
    lineHeight: '1.3'
  h3:
    fontFamily: Rajdhani
    fontSize: 20px
    fontWeight: '600'
    lineHeight: '1.4'
  body-lg:
    fontFamily: Nunito
    fontSize: 18px
    fontWeight: '600'
    lineHeight: '1.6'
  body-md:
    fontFamily: Nunito
    fontSize: 16px
    fontWeight: '400'
    lineHeight: '1.5'
  data-mono:
    fontFamily: JetBrains Mono
    fontSize: 14px
    fontWeight: '500'
    lineHeight: '1.4'
  label-caps:
    fontFamily: Rajdhani
    fontSize: 12px
    fontWeight: '700'
    lineHeight: '1.2'
    letterSpacing: 0.1em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  base: 4px
  xs: 8px
  sm: 16px
  md: 24px
  lg: 40px
  xl: 64px
  gutter: 16px
  margin: 32px
---

## Brand & Style

The design system is engineered to evoke a sense of high-performance synchronization and futuristic reliability. It targets tech-savvy power users who view their PC and mobile devices as a unified ecosystem. The aesthetic is "Cyber-Premium"—a blend of sophisticated dark-mode professionalism and high-energy neon accents.

The style leverages **Glassmorphism** to create a sense of depth and physical layering within a digital environment. By utilizing translucent surfaces and vibrant background blurs, the interface feels lightweight and integrated. This is grounded by a strict geometric grid and high-contrast typography, ensuring that the "neon" elements act as functional beacons rather than mere decoration.

## Colors

The palette is anchored in **Deep Navy** to provide a high-contrast foundation for vibrant data visualization and interactive elements. 

- **Primary (Electric Cyan):** Used for critical actions, active states, and focus indicators. It represents the "live" connection between devices.
- **Secondary (Deep Purple):** Used for secondary branding elements, depth in gradients, and distinguishing between different data streams.
- **Surface Strategy:** Backgrounds transition from the darkest point (Main App) to slightly lighter variants (Sidebar/Cards) to establish hierarchy. All cards utilize a 60% opacity fill to allow background gradients or content to softly bleed through, maintaining a cohesive "glass" environment.

## Typography

The typography system uses three distinct typefaces to separate intent:
1. **Rajdhani (Headings):** A squared, technical font that reinforces the futuristic, industrial aesthetic of the brand.
2. **Nunito (Body):** Provides a friendly, highly readable contrast to the sharp headings, ensuring long-form content is accessible.
3. **JetBrains Mono (Data):** Reserved for technical strings, device IDs, IP addresses, and file paths to emphasize precision.

All headings should be treated with "Optical Kerning" and a slight negative letter-spacing for a tighter, premium feel. Labels and small metadata should be uppercase with wide tracking for legibility against dark backgrounds.

## Layout & Spacing

The layout follows a **Fluid Grid** model with fixed-width sidebars. The main content area utilizes a 12-column grid that scales with the window size, while the sidebar remains at a constant 280px.

- **Rhythm:** A 4px baseline grid ensures vertical alignment.
- **Safe Areas:** Standard window margins are set to 32px to provide breathing room against the glass edges.
- **Custom Title Bar:** The app utilizes a 48px height custom title bar that integrates with the sidebar color, removing standard Windows chrome for a bespoke experience.

## Elevation & Depth

Elevation in the design system is communicated through **Layered Glassmorphism** rather than traditional drop shadows.

1. **Level 0 (Background):** Deep Navy (#0A0E1A).
2. **Level 1 (Sidebar):** Slightly lighter navy (#0D1220), creating a vertical structural anchor.
3. **Level 2 (Cards):** Surface cards with 60% opacity and a subtle 1px white border (8% opacity). This layer uses a 20px backdrop-filter blur.
4. **Level 3 (Modals/Popovers):** Surface Elevated (#1A2236) with a 24px Cyan Glow shadow (15% opacity) to indicate active user focus.

## Shapes

The shape language is "Soft-Tech." While the brand is futuristic, the use of generous corner radii makes the software feel modern and approachable.

- **Cards:** Defined at 16px to create a soft container for content.
- **Interactive Elements:** Inputs and buttons use a tighter 8px radius to signify their utility and "clickable" nature.
- **Visual Continuity:** Elements nested within cards should have their radius reduced by 4px (e.g., a button inside a card should ideally be 12px or 8px) to maintain geometric harmony.

## Components

### Buttons
- **Primary:** Gradient fill (Electric Cyan to Deep Purple) with white text. On hover, apply a 20px Cyan outer glow.
- **Secondary:** Transparent with an Electric Cyan 1px border. 

### Input Fields
- **Default:** Background Surface Elevated (#1A2236), 8px radius, subtle 8% white border.
- **Focus:** 1px Electric Cyan border with a 4px `00E5FF` outer glow. Text shifts to white.

### Cards
- **Construction:** 60% opacity #121929 fill, 20px backdrop blur, and 16px corner radius.
- **Border:** 1px solid white at 8% opacity to catch the "light" at the edges.

### Status Indicators (Pills)
- Use the **JetBrains Mono** font for status tags. 
- **Active:** Electric Cyan text with a 10% opacity cyan background.
- **Syncing:** Deep Purple text with a subtle pulse animation.

### Scrollbars
- Custom 4px width. Track is transparent; thumb is a 20% opacity Electric Cyan pill.