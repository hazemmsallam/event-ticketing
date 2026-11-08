from http.server import BaseHTTPRequestHandler, HTTPServer
import json

events = [
    dict(id=1, name='Sami Yusuf Live in Concert', category='Music', imageUrl=None,
         tagline='An unforgettable night of music', featured=True, tags=['Live'],
         startAt='2026-11-21T17:00:00Z', status='PUBLISHED', hallName='Amman Arena',
         hallAddress='Amman, Jordan', seated=True),
    dict(id=2, name='Cinema Under the Stars', category='Cinema', imageUrl=None,
         tagline='A magical open-air screening', featured=False, tags=['Outdoor'],
         startAt='2026-11-25T17:30:00Z', status='PUBLISHED', hallName='The Boulevard',
         hallAddress='Abdali, Amman', seated=False),
]


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = self.path.split('?')[0]
        if path == '/api/events/available':
            payload = events
        elif path == '/api/events/1':
            payload = dict(events[0], description='Experience an evening of music, atmosphere and connection.',
                           pricing=[dict(sectionId=1, sectionName='VIP', price=80),
                                    dict(sectionId=2, sectionName='Class A', price=50),
                                    dict(sectionId=3, sectionName='General admission', price=25)])
        elif path == '/api/events/2':
            payload = dict(events[1], description='Enjoy a film with friends under the night sky.',
                           pricing=[dict(sectionId=4, sectionName='General admission', price=20)])
        else:
            self.send_error(404)
            return
        data = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)


HTTPServer(('0.0.0.0', 18082), Handler).serve_forever()
