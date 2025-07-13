#!/usr/bin/env python3
"""
Guida Assistant Server
Receives images and text from the Android app and processes them.
"""

from flask import Flask, request, jsonify
import os
import datetime
from werkzeug.utils import secure_filename

app = Flask(__name__)

# Configuration
UPLOAD_FOLDER = 'uploads'
ALLOWED_EXTENSIONS = {'png', 'jpg', 'jpeg', 'gif'}

# Create uploads directory if it doesn't exist
os.makedirs(UPLOAD_FOLDER, exist_ok=True)

print('server.py is running')

def allowed_file(filename):
    """Check if the file extension is allowed."""
    return '.' in filename and \
           filename.rsplit('.', 1)[1].lower() in ALLOWED_EXTENSIONS

@app.route('/upload', methods=['POST'])
def upload():
    """Handle image and text upload from Android app."""
    try:
        # Get the text from form data
        text = request.form.get('text', '')
        
        # Get the image file
        if 'image' not in request.files:
            return jsonify({'error': 'No image file provided'}), 400
        
        file = request.files['image']
        if file.filename == '':
            return jsonify({'error': 'No image file selected'}), 400
        
        if file and file.filename and allowed_file(file.filename):
            # Create a unique filename with timestamp
            timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
            filename = f"guida_{timestamp}_{secure_filename(file.filename)}"
            filepath = os.path.join(UPLOAD_FOLDER, filename)
            
            # Save the file
            file.save(filepath)
            
            # Log the received data
            print(f"\n=== Received Data ===")
            print(f"Timestamp: {datetime.datetime.now()}")
            print(f"Text: {text}")
            print(f"Image saved: {filepath}")
            print(f"Image size: {os.path.getsize(filepath)} bytes")
            print("====================\n")
            
            # Here you can add your AI processing logic
            # For now, we'll just return a simple response
            response_text = process_data(text, filepath)
            
            return jsonify({
                'status': 'success',
                'message': 'Data received successfully',
                'received_text': text,
                'image_path': filepath,
                'response': response_text
            }), 200
        else:
            return jsonify({'error': 'Invalid file type'}), 400
            
    except Exception as e:
        print(f"Error processing upload: {str(e)}")
        return jsonify({'error': f'Server error: {str(e)}'}), 500

def process_data(text, image_path):
    """
    Process the received text and image.
    This is where you can add your AI processing logic.
    """
    # Simple example processing
    if text.strip():
        return f"Received your message: '{text}'. I'm processing the image at {image_path}."
    else:
        return f"Received an image without text. Processing image at {image_path}."

@app.route('/health', methods=['GET'])
def health_check():
    """Health check endpoint."""
    return jsonify({
        'status': 'healthy',
        'timestamp': datetime.datetime.now().isoformat(),
        'uploads_folder': UPLOAD_FOLDER
    }), 200

@app.route('/', methods=['GET'])
def index():
    """Root endpoint with basic info."""
    return jsonify({
        'service': 'Guida Assistant Server',
        'version': '1.0',
        'endpoints': {
            'upload': '/upload (POST) - Receive image and text',
            'health': '/health (GET) - Health check'
        }
    }), 200

if __name__ == '__main__':
    print("=== Guida Assistant Server ===")
    print("Starting server on http://0.0.0.0:5000")
    print("Uploads will be saved to:", UPLOAD_FOLDER)
    print("Make sure your Android app is configured to send to this server!")
    print("Press Ctrl+C to stop the server")
    print("==============================\n")
    
    # Run the server
    app.run(host='0.0.0.0', port=5000, debug=True) 