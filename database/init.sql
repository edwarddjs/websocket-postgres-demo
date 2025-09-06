-- Create table for messages
CREATE TABLE message (
    id SERIAL PRIMARY KEY,
    destination_email TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT now()
);

-- Create a function that sends a NOTIFY event
CREATE OR REPLACE FUNCTION notify_new_message() RETURNS trigger AS $$
BEGIN

    PERFORM pg_notify('tickets_channel',
        json_build_object(
            'destinationEmail', NEW.destination_email,
            'content', NEW.content
        )::text
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create trigger to fire on insert
CREATE TRIGGER messages_notify_trigger
AFTER INSERT ON message
FOR EACH ROW EXECUTE FUNCTION notify_new_message();