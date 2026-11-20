"""Build the app copy of the game: python3 make_app_html.py ../index.html
Reads game.html (browser version) next to this script and swaps the Google Fonts link
for the fonts bundled inside the APK (assets/fonts)."""
import os,re,sys
here=os.path.dirname(os.path.abspath(__file__))
html=open(os.path.join(here,'game.html')).read()
fonts=open(os.path.join(here,'fonts.css')).read()
html,n=re.subn(r'<link rel="preconnect"[^>]*>\n<link href="https://fonts.googleapis.com[^>]*>',lambda m:fonts,html)
assert n==1 and 'fonts.googleapis' not in html, 'font link not found'
open(sys.argv[1],'w').write(html)
