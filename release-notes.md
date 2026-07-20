### Fixed
- Agendula no longer crashes on launch. Every 0.3.0 install was affected: the
  release build stripped a constructor that the background-work scheduler needs
  to open its database, and that happens before the app draws anything.

