# Project Menu

A small, local, single-page app for organizing personal projects into a tree of
domains, projects, and subtasks, with tags for context (e.g. "From home",
"Quick (< 15 min)") so you can pick something that fits the moment.

Everything runs on your own computer. Nothing is sent anywhere.

## Running it

Requires Python 3 (no extra packages).

- Windows: double-click `Open Project Menu.bat`
- Any OS: `python server.py` (optional: `python server.py 9000 --no-browser`)

It opens http://127.0.0.1:8765. The server only listens on 127.0.0.1.

Your data is saved to `projects.json`, and attached files go into `media/`, both
next to `server.py`. On first run the app starts with sample projects. Replace
them with your own.

You can also open `index.html` directly in a browser without the server. Data is
then kept in the browser's localStorage, and file attachments are unavailable.

## Keyboard

- Arrow keys move the highlight through the tree. Right goes deeper (down to
  subtasks), Left goes back up, and Left on a top-level item clears it.
- Escape clears the highlight.
- `/` jumps to search, `?` opens help.

## Appearance

The app follows your system's light or dark setting. Use More > Theme to
force light or dark.

## Touch (phones and tablets)

- Tap a project to highlight it and open its details; tap empty space to clear
  the highlight. The Android Back button closes dialogs and menus, then
  details, then clears the highlight.
- Drag with one finger to pan the tree, pinch to zoom, or use the + and -
  buttons. Press and hold a project to collapse or expand it.
- In the To-do view, press and hold a tile, then drag it to another day or
  onto an Unscheduled button. You can also tap a tile's date to change it.

## Tree view options

- Show subtasks: display subtasks as nodes in the tree.
- Align tips: line up all visible end nodes in one column.
- Expand all / collapse branches.
